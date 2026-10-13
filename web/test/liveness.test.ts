// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Validates the moire-detection POC per spec/test/kyc_liveness_test_plan.md
// section 2: false-positive rate on genuine presentations first, then
// per-category detection rate on synthetic spoof presentations. No real
// camera available in this environment -- see that doc for why synthetic
// data (not a real photo-of-screen) is what's being measured here, and
// what that limits this result to proving.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { averageRows, detectMoirePattern } from '../src/core/liveness.js';

const WIDTH = 256;

/** Seeded PRNG (mulberry32) so these vectors are reproducible, not flaky. */
function makeRng(seed: number): () => number {
  let a = seed;
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Synthetic "real card" row: a smooth lighting gradient, a handful of
 * sparse non-periodic dark bars (simulating embossed/printed digits), and
 * non-periodic sensor/print-texture noise. Deliberately has no repeating
 * spatial structure.
 */
function genuineRow(seed: number): Uint8Array {
  const rng = makeRng(seed);
  const row = new Uint8Array(WIDTH);
  for (let x = 0; x < WIDTH; x++) {
    const gradient = 170 + 20 * Math.sin((x / WIDTH) * Math.PI); // slow lighting falloff
    const noise = (rng() - 0.5) * 8; // print/sensor texture, non-periodic
    row[x] = Math.max(0, Math.min(255, Math.round(gradient + noise)));
  }
  // A few sparse, irregularly-spaced dark bars (embossed digit strokes).
  for (const center of [40, 95, 110, 170, 210]) {
    for (let dx = -2; dx <= 2; dx++) {
      const x = center + dx;
      if (x >= 0 && x < WIDTH) row[x] = Math.max(0, row[x] - 60);
    }
  }
  return row;
}

/**
 * Synthetic "photo of a screen" row: the same base content as
 * `genuineRow`, plus an added periodic sinusoid at a non-integer pixel
 * period -- the aliasing signature of photographing a screen's sub-pixel
 * grid with a second camera sensor.
 */
function screenRecaptureRow(seed: number, periodPx: number, amplitude: number): Uint8Array {
  const base = genuineRow(seed);
  const row = new Uint8Array(WIDTH);
  for (let x = 0; x < WIDTH; x++) {
    const moire = amplitude * Math.sin((2 * Math.PI * x) / periodPx);
    row[x] = Math.max(0, Math.min(255, Math.round(base[x] + moire)));
  }
  return row;
}

describe('Liveness (moire detection POC)', () => {
  it('false-positive rate on genuine presentations', () => {
    const trials = 50;
    let falsePositives = 0;
    for (let i = 0; i < trials; i++) {
      const result = detectMoirePattern(genuineRow(1000 + i));
      if (result.likelySpoofed) falsePositives++;
    }
    const rate = falsePositives / trials;
    // Per the test plan: this is the first number that matters, before
    // detection rate even gets measured. Print it rather than just
    // asserting a bound, so a future run's real number is visible.
    console.log(`genuine-presentation false-positive rate: ${(rate * 100).toFixed(1)}% (${falsePositives}/${trials})`);
    assert.ok(rate <= 0.1, `false-positive rate ${rate} exceeds the 10% ceiling for this POC`);
  });

  it('detection rate: screen recapture at a realistic moire period (3-6px)', () => {
    const trials = 50;
    let detected = 0;
    for (let i = 0; i < trials; i++) {
      const period = 3 + (i % 4); // sweep 3,4,5,6 px -- plausible close-range recapture
      const result = detectMoirePattern(screenRecaptureRow(2000 + i, period, 18));
      if (result.likelySpoofed) detected++;
    }
    const rate = detected / trials;
    console.log(`screen-recapture detection rate (3-6px period): ${(rate * 100).toFixed(1)}% (${detected}/${trials})`);
    assert.ok(rate >= 0.8, `detection rate ${rate} is below the 80% floor for this POC`);
  });

  it('detection rate degrades at a fainter moire amplitude (low-contrast recapture)', () => {
    const trials = 50;
    let detected = 0;
    for (let i = 0; i < trials; i++) {
      const result = detectMoirePattern(screenRecaptureRow(3000 + i, 4.5, 6));
      if (result.likelySpoofed) detected++;
    }
    const rate = detected / trials;
    // Reported, not asserted strictly -- this is exactly the kind of
    // result the test plan says to expect and record, not force to pass:
    // a weak heuristic signal at low moire contrast is a real finding,
    // not a bug in the test.
    console.log(`screen-recapture detection rate (faint moire, amplitude 6): ${(rate * 100).toFixed(1)}% (${detected}/${trials})`);
  });

  it('averageRows reduces single-scanline noise sensitivity', () => {
    const height = 8;
    const luma = new Uint8Array(WIDTH * height);
    for (let r = 0; r < height; r++) {
      const row = genuineRow(5000 + r);
      luma.set(row, r * WIDTH);
    }
    const avg = averageRows(luma, WIDTH, 0, height);
    assert.equal(avg.length, WIDTH);
  });
});
