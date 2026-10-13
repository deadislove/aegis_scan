// Copyright 2026 AegisScan Authors. All rights reserved.
//
// EXPERIMENTAL / POC prototype -- see spec/future/20261010_kyc_liveness.md
// and spec/test/kyc_liveness_test_plan.md. Not wired into any camera
// pipeline; this module exists to answer one specific question from the
// scoping doc before any five-platform integration effort is spent: does
// a cheap, no-training-data heuristic actually distinguish a real printed
// card/document from a recaptured photo of a screen displaying one?
//
// The signal: photographing an LCD/OLED screen's sub-pixel grid with
// another camera sensor produces a periodic aliasing pattern (moire) that
// a genuinely flat printed surface does not have. This heuristic looks
// for that periodicity via row-wise autocorrelation of a Laplacian-
// filtered luma signal -- the same high-pass primitive FrameAnalyzer's
// focus score already uses in the C++ core (fintech_core.hpp), applied
// here to detect unwanted periodic structure rather than overall sharpness.

/** Candidate moire period range, in pixels, for a typical close-up
 * screen recapture (phone camera a few inches from another screen). */
const MIN_PERIOD_LAG = 2;
const MAX_PERIOD_LAG = 20;

export interface LivenessResult {
  /** True if a periodic (moire-like) pattern was detected. */
  likelySpoofed: boolean;
  /** Normalized autocorrelation strength at the detected period (0-1). */
  periodicityScore: number;
  /** Pixel lag of the strongest periodic peak, if any. */
  periodLag: number | null;
}

/** Discrete Laplacian of one row (zero at the two endpoints, matching the
 * interior-only convention FrameAnalyzer uses for its 2D version). */
function rowLaplacian(row: Float64Array): Float64Array {
  const out = new Float64Array(row.length);
  for (let x = 1; x < row.length - 1; x++) {
    out[x] = 2 * row[x] - row[x - 1] - row[x + 1];
  }
  return out;
}

/** Normalized autocorrelation at `lag`: 1.0 means the signal perfectly
 * repeats itself at that spacing, 0.0 means no linear relationship. */
function autocorrelation(signal: Float64Array, lag: number): number {
  const n = signal.length - lag;
  if (n <= 1) return 0;
  let mean = 0;
  for (let i = 0; i < signal.length; i++) mean += signal[i];
  mean /= signal.length;

  let num = 0;
  let denom = 0;
  for (let i = 0; i < signal.length; i++) {
    const d = signal[i] - mean;
    denom += d * d;
  }
  for (let i = 0; i < n; i++) {
    num += (signal[i] - mean) * (signal[i + lag] - mean);
  }
  return denom === 0 ? 0 : num / denom;
}

/**
 * Scans one luma row for a moire-like periodic pattern. `row` is a single
 * horizontal strip of 8-bit luma values (averaging a few adjacent rows
 * before calling this reduces sensitivity to a single noisy scanline, but
 * isn't required).
 *
 * This is a POC heuristic, not a validated anti-spoofing control -- see
 * spec/test/kyc_liveness_test_plan.md section 2 for the false-positive/
 * detection-rate methodology this needs to clear before it's anything
 * more than that.
 */
export function detectMoirePattern(row: Uint8ClampedArray | Uint8Array, threshold = 0.5): LivenessResult {
  const luma = new Float64Array(row.length);
  for (let i = 0; i < row.length; i++) luma[i] = row[i];
  const lap = rowLaplacian(luma);

  let bestLag: number | null = null;
  let bestScore = 0;
  for (let lag = MIN_PERIOD_LAG; lag <= MAX_PERIOD_LAG && lag < lap.length / 2; lag++) {
    const score = autocorrelation(lap, lag);
    if (score > bestScore) {
      bestScore = score;
      bestLag = lag;
    }
  }

  return {
    likelySpoofed: bestScore >= threshold,
    periodicityScore: bestScore,
    periodLag: bestScore >= threshold ? bestLag : null,
  };
}

/** Averages `rowCount` rows starting at `startRow` into one luma strip,
 * reducing single-scanline noise sensitivity before periodicity analysis. */
export function averageRows(
  luma: Uint8ClampedArray | Uint8Array, width: number, startRow: number, rowCount: number,
): Uint8ClampedArray {
  const out = new Uint8ClampedArray(width);
  const sums = new Float64Array(width);
  for (let r = 0; r < rowCount; r++) {
    const base = (startRow + r) * width;
    for (let x = 0; x < width; x++) sums[x] += luma[base + x];
  }
  for (let x = 0; x < width; x++) out[x] = sums[x] / rowCount;
  return out;
}
