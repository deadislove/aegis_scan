// Copyright 2026 AegisScan Authors. All rights reserved.
//
// TypeScript port of aegisscan::core::SpatialMatcher.

import { repairCharCode } from './luhn.js';

/** Axis-aligned box in image pixels, top-left origin. */
export interface BoundingBox {
  x: number;
  y: number;
  width: number;
  height: number;
}
export interface BarcodeItem {
  payload: string;
  box: BoundingBox;
  format?: string;
}
export interface TextItem {
  text: string;
  box: BoundingBox;
}
export type Alignment = 'text-below' | 'text-above' | 'overlapping' | 'side-by-side' | 'diagonal';
export interface MatchResult {
  barcode: BarcodeItem;
  associatedText: string;
  normalizedDistance: number;
  alignment: Alignment;
}
export interface SpatialMatcherOptions {
  frameWidth: number;
  frameHeight: number;
  maxGapRatio: number;
  minAxisOverlap: number;
  stackedWeight: number;
  sideBySideWeight: number;
  diagonalWeight: number;
  minOverlapChars: number;
}

export const DEFAULT_MATCHER_OPTIONS: Readonly<SpatialMatcherOptions> = Object.freeze({
  frameWidth: 0,
  frameHeight: 0,
  maxGapRatio: 2,
  minAxisOverlap: 0.3,
  stackedWeight: 0.5,
  sideBySideWeight: 1,
  diagonalWeight: 1.5,
  minOverlapChars: 4,
});

const MAX_FOLDED = 128;

const isValidBox = (b: BoundingBox): boolean =>
  Number.isFinite(b.x) && Number.isFinite(b.y) && Number.isFinite(b.width) &&
  Number.isFinite(b.height) && b.width > 0 && b.height > 0;

/** Matcher with reusable scratch buffers (one instance per worker). */
export class SpatialMatcher {
  private readonly opts: SpatialMatcherOptions;
  private readonly foldA = new Uint8Array(MAX_FOLDED);
  private readonly foldB = new Uint8Array(MAX_FOLDED);
  private readonly rowA = new Uint16Array(MAX_FOLDED + 1);
  private readonly rowB = new Uint16Array(MAX_FOLDED + 1);

  constructor(options: Partial<SpatialMatcherOptions> = {}) {
    this.opts = { ...DEFAULT_MATCHER_OPTIONS, ...options };
  }

  matchBarcodesWithText(barcodes: readonly BarcodeItem[], texts: readonly TextItem[]): MatchResult[] {
    if (barcodes.length === 0 || texts.length === 0) return [];
    const [ox, oy, scale] = this.frame(barcodes, texts);
    const candidates: Array<{ b: number; t: number; score: number; dist: number; align: Alignment }> = [];

    barcodes.forEach((bc, bi) => {
      if (!isValidBox(bc.box)) return;
      const pLen = this.fold(bc.payload, this.foldA);
      if (pLen === 0) return;
      texts.forEach((tx, ti) => {
        if (!isValidBox(tx.box) || !this.withinGap(bc.box, tx.box)) return;
        const tLen = this.fold(tx.text, this.foldB);
        if (tLen === 0) return;
        const shorter = Math.min(pLen, tLen);
        const required = Math.min(this.opts.minOverlapChars, shorter);
        const overlap = this.lcs(pLen, tLen);
        if (required === 0 || overlap < required) return;
        const align = this.classify(bc.box, tx.box);
        const dist = this.centerDistance(bc.box, tx.box, ox, oy, scale);
        const score = dist * this.weight(align) * (1 - (0.5 * overlap) / shorter);
        candidates.push({ b: bi, t: ti, score, dist, align });
      });
    });
    this.foldA.fill(0);
    this.foldB.fill(0);

    candidates.sort((a, z) => a.score - z.score || a.b - z.b || a.t - z.t);
    const usedB = new Uint8Array(barcodes.length);
    const usedT = new Uint8Array(texts.length);
    const picked = candidates.filter((c) => {
      if (usedB[c.b] || usedT[c.t]) return false;
      usedB[c.b] = 1;
      usedT[c.t] = 1;
      return true;
    });
    picked.sort((a, z) => a.b - z.b);
    return picked.map((c) => ({
      barcode: barcodes[c.b],
      associatedText: texts[c.t].text,
      normalizedDistance: c.dist,
      alignment: c.align,
    }));
  }

  private frame(barcodes: readonly BarcodeItem[], texts: readonly TextItem[]): [number, number, number] {
    if (this.opts.frameWidth > 0 && this.opts.frameHeight > 0) {
      return [0, 0, Math.max(this.opts.frameWidth, this.opts.frameHeight)];
    }
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const { box: b } of [...barcodes, ...texts]) {
      if (!isValidBox(b)) continue;
      minX = Math.min(minX, b.x);
      minY = Math.min(minY, b.y);
      maxX = Math.max(maxX, b.x + b.width);
      maxY = Math.max(maxY, b.y + b.height);
    }
    if (minX > maxX) return [0, 0, 1];
    const extent = Math.max(maxX - minX, maxY - minY);
    return [minX, minY, Number.isFinite(extent) && extent > 0 ? extent : 1];
  }

  private centerDistance(a: BoundingBox, b: BoundingBox, ox: number, oy: number, s: number): number {
    const ax = (a.x + a.width / 2 - ox) / s;
    const ay = (a.y + a.height / 2 - oy) / s;
    const bx = (b.x + b.width / 2 - ox) / s;
    const by = (b.y + b.height / 2 - oy) / s;
    return Math.hypot(ax - bx, ay - by);
  }

  private withinGap(a: BoundingBox, b: BoundingBox): boolean {
    const gx = Math.max(0, b.x - (a.x + a.width), a.x - (b.x + b.width));
    const gy = Math.max(0, b.y - (a.y + a.height), a.y - (b.y + b.height));
    return Math.hypot(gx, gy) <= this.opts.maxGapRatio * Math.max(a.height, b.height);
  }

  private static axisOverlap(a0: number, a1: number, b0: number, b1: number): number {
    const overlap = Math.min(a1, b1) - Math.max(a0, b0);
    const shorter = Math.min(a1 - a0, b1 - b0);
    return overlap <= 0 || shorter <= 0 ? 0 : overlap / shorter;
  }

  private classify(bc: BoundingBox, tx: BoundingBox): Alignment {
    const col = SpatialMatcher.axisOverlap(bc.x, bc.x + bc.width, tx.x, tx.x + tx.width) >= this.opts.minAxisOverlap;
    const row = SpatialMatcher.axisOverlap(bc.y, bc.y + bc.height, tx.y, tx.y + tx.height) >= this.opts.minAxisOverlap;
    if (col && row) return 'overlapping';
    if (col) return tx.y + tx.height / 2 >= bc.y + bc.height / 2 ? 'text-below' : 'text-above';
    if (row) return 'side-by-side';
    return 'diagonal';
  }

  private weight(a: Alignment): number {
    if (a === 'side-by-side') return this.opts.sideBySideWeight;
    if (a === 'diagonal') return this.opts.diagonalWeight;
    return this.opts.stackedWeight;
  }

  /** Upper-case, keep [0-9A-Z], fold OCR-confusable letters to digits. */
  private fold(s: string, out: Uint8Array): number {
    let n = 0;
    for (let i = 0; i < s.length && n < out.length; i++) {
      let c = s.charCodeAt(i);
      if (c >= 0x61 && c <= 0x7a) c -= 0x20;
      const alnum = (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5a);
      if (!alnum) continue;
      const r = repairCharCode(c);
      out[n++] = r !== 0 ? r : c;
    }
    return n;
  }

  /** Longest common substring of foldA[0..a) and foldB[0..b). */
  private lcs(aLen: number, bLen: number): number {
    let prev = this.rowA;
    let curr = this.rowB;
    prev.fill(0);
    curr.fill(0);
    let best = 0;
    for (let i = 1; i <= aLen; i++) {
      for (let j = 1; j <= bLen; j++) {
        curr[j] = this.foldA[i - 1] === this.foldB[j - 1] ? prev[j - 1] + 1 : 0;
        if (curr[j] > best) best = curr[j];
      }
      [prev, curr] = [curr, prev];
      curr[0] = 0;
    }
    return best;
  }
}
