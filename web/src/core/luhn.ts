// Copyright 2026 AegisScan Authors. All rights reserved.
//
// TypeScript port of aegisscan::core::LuhnValidator (same behavior and test
// vectors). PANs are produced as Uint8Array (ASCII digits) so they can be
// wiped with .fill(0). JS strings are immutable and can't be cleared.

export enum CardBrand {
  Unknown = 0,
  Visa = 1,
  Mastercard = 2,
  Amex = 3,
  Jcb = 4,
}

export const CARD_BRAND_NAMES = ['unknown', 'visa', 'mastercard', 'amex', 'jcb'] as const;

const MIN_PAN = 13;
const MAX_PAN = 19;
const RUN_CAPACITY = 64;
const MAX_GROUPS = 32;
const LENGTH_PREFERENCE = [16, 15, 19, 14, 13, 17, 18] as const;
const ZERO = 0x30;
const NINE = 0x39;

/** OCR repair table: char code -> digit char code (0 = not repairable). */
const REPAIR = new Uint8Array(128);
for (let c = ZERO; c <= NINE; c++) REPAIR[c] = c;
for (const ch of 'ODQo') REPAIR[ch.charCodeAt(0)] = 0x30;
for (const ch of 'Il|i') REPAIR[ch.charCodeAt(0)] = 0x31;
for (const ch of 'Zz') REPAIR[ch.charCodeAt(0)] = 0x32;
for (const ch of 'Ss$') REPAIR[ch.charCodeAt(0)] = 0x35;
REPAIR['B'.charCodeAt(0)] = 0x38;
REPAIR['A'.charCodeAt(0)] = 0x34;

const GROUP_SEPARATORS = new Set([0x20, 0x2d, 0x2e, 0x09]); // space - . \t
const HARD_BREAKS = new Set(Array.from('\n\r\v\f:;,/\\#()[]{}*="\'', (c) => c.charCodeAt(0)));

/** Repairs a single character code. Returns 0 if it can't be a digit. */
export function repairCharCode(code: number): number {
  return code < 128 ? REPAIR[code] : 0;
}

/** Standard Luhn Mod-10 over ASCII digit codes in [start, start+len). */
function luhnCodes(d: ArrayLike<number>, start: number, len: number): boolean {
  if (len < 2) return false;
  let sum = 0;
  let dbl = false;
  for (let i = start + len - 1; i >= start; i--) {
    const c = d[i];
    if (c < ZERO || c > NINE) return false;
    let v = c - ZERO;
    if (dbl) {
      v *= 2;
      if (v > 9) v -= 9;
    }
    sum += v;
    dbl = !dbl;
  }
  return sum % 10 === 0;
}

function brandCodes(d: ArrayLike<number>, start: number, n: number): CardBrand {
  if (n < MIN_PAN || n > MAX_PAN) return CardBrand.Unknown;
  for (let i = start; i < start + n; i++) {
    if (d[i] < ZERO || d[i] > NINE) return CardBrand.Unknown;
  }
  const prefix = (k: number): number => {
    let v = 0;
    for (let i = 0; i < k; i++) v = v * 10 + (d[start + i] - ZERO);
    return v;
  };
  const p1 = prefix(1);
  const p2 = prefix(2);
  const p4 = prefix(4);
  if (p1 === 4 && (n === 13 || n === 16 || n === 19)) return CardBrand.Visa;
  if (n === 16 && ((p2 >= 51 && p2 <= 55) || (p4 >= 2221 && p4 <= 2720))) return CardBrand.Mastercard;
  if (n === 15 && (p2 === 34 || p2 === 37)) return CardBrand.Amex;
  if (n >= 16 && p4 >= 3528 && p4 <= 3589) return CardBrand.Jcb;
  return CardBrand.Unknown;
}

const toCodes = (s: string): Uint8Array => {
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 0xff;
  return out;
};

/** Standard Luhn Mod-10 check (digits only). */
export function validate(number: string): boolean {
  if (number.length < 2) return false;
  for (let i = 0; i < number.length; i++) {
    const c = number.charCodeAt(i);
    if (c < ZERO || c > NINE) return false;
  }
  return luhnCodes(toCodes(number), 0, number.length);
}

/** Brand of an all-digit PAN string. */
export function detectBrand(digits: string): CardBrand {
  return brandCodes(toCodes(digits), 0, digits.length);
}

/** A PAN in a wipeable buffer. Call wipe() as soon as you're done. */
export interface ExtractedPan {
  /** ASCII digit codes. Zeroed by wipe(). */
  readonly digits: Uint8Array;
  readonly brand: CardBrand;
  readonly last4: string;
  wipe(): void;
}

function makePan(src: Uint8Array, start: number, len: number, brand: CardBrand): ExtractedPan {
  const digits = src.slice(start, start + len);
  const last4 = String.fromCharCode(...digits.subarray(len - 4));
  return {
    digits,
    brand,
    last4,
    wipe: () => digits.fill(0),
  };
}

/**
 * Repairs OCR confusions (O/D/Q→0, I/l/|→1, Z→2, S/$→5, B→8, A→4) and returns
 * the first 13–19 digit candidate with a known BIN that passes Luhn. Scratch
 * buffers are zeroed before returning.
 */
export function extractCard(rawOcr: string): ExtractedPan | null {
  const run = new Uint8Array(RUN_CAPACITY);
  const bounds = new Int32Array(MAX_GROUPS + 1);
  const token = new Uint8Array(RUN_CAPACITY);
  let runSize = 0;
  let groups = 0;
  let realDigits = 0;
  let longGroup = false;
  let tokSize = 0;
  let tokReal = 0;
  let tokValid = true;
  let tokStarted = false;
  let found: ExtractedPan | null = null;

  const isBoundary = (pos: number, from: number): boolean => {
    for (let g = from; g <= groups; g++) {
      if (bounds[g] === pos) return true;
      if (bounds[g] > pos) return false;
    }
    return false;
  };
  const attempt = (start: number, len: number): ExtractedPan | null => {
    const brand = brandCodes(run, start, len);
    if (brand === CardBrand.Unknown || !luhnCodes(run, start, len)) return null;
    return makePan(run, start, len, brand);
  };
  const evaluateRun = (): ExtractedPan | null => {
    if (runSize < MIN_PAN || realDigits * 2 < runSize) return null;
    for (let g = 0; g < groups; g++) {
      const start = bounds[g];
      for (const len of LENGTH_PREFERENCE) {
        const end = start + len;
        if (end > runSize || !isBoundary(end, g + 1)) continue;
        const pan = attempt(start, len);
        if (pan) return pan;
      }
    }
    if (longGroup) {
      for (let start = 0; start + MIN_PAN <= runSize; start++) {
        for (const len of LENGTH_PREFERENCE) {
          if (start + len > runSize) continue;
          const pan = attempt(start, len);
          if (pan) return pan;
        }
      }
    }
    return null;
  };
  const flushRun = (): void => {
    if (!found) found = evaluateRun();
    run.fill(0, 0, runSize);
    runSize = 0;
    groups = 0;
    realDigits = 0;
    longGroup = false;
  };
  const flushToken = (): void => {
    if (tokStarted) {
      if (tokValid) {
        if (runSize + tokSize > RUN_CAPACITY || groups >= MAX_GROUPS) flushRun();
        run.set(token.subarray(0, tokSize), runSize);
        runSize += tokSize;
        realDigits += tokReal;
        groups++;
        bounds[groups] = runSize;
        if (tokSize > MAX_PAN) longGroup = true;
      } else {
        flushRun();
      }
    }
    token.fill(0, 0, tokSize);
    tokSize = 0;
    tokReal = 0;
    tokValid = true;
    tokStarted = false;
  };

  try {
    for (let i = 0; i <= rawOcr.length && !found; i++) {
      const c = i < rawOcr.length ? rawOcr.charCodeAt(i) : 0x0a;
      if (GROUP_SEPARATORS.has(c)) {
        flushToken();
        continue;
      }
      if (HARD_BREAKS.has(c)) {
        flushToken();
        flushRun();
        continue;
      }
      tokStarted = true;
      if (!tokValid) continue;
      const r = repairCharCode(c);
      if (r === 0 || tokSize >= RUN_CAPACITY) {
        tokValid = false;
        continue;
      }
      token[tokSize++] = r;
      if (c >= ZERO && c <= NINE) tokReal++;
    }
  } finally {
    run.fill(0);
    token.fill(0);
  }
  return found;
}

/** Spec-style convenience API. Prefer extractCard() (wipeable). */
export function repairAndExtract(rawOcr: string): string | null {
  const pan = extractCard(rawOcr);
  if (!pan) return null;
  const s = String.fromCharCode(...pan.digits);
  pan.wipe();
  return s;
}
