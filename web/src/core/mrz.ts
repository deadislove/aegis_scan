// Copyright 2026 AegisScan Authors. All rights reserved.
//
// TypeScript port of aegisscan::core::extract_mrz (ICAO Doc 9303 MRZ
// parsing, same behavior and test vectors as the C++ core).

import { repairCharCode } from './luhn.js';

const ZERO = 0x30;
const NINE = 0x39;
const A = 0x41;
const Z = 0x5a;

/**
 * Parsed TD1 (3-line, ID card) or TD3 (2-line, passport) MRZ.
 *
 * Unlike expiry/cardholder name, every field here is backed by an ICAO
 * 9303 check digit (same mental model as Luhn backing a PAN) --
 * `*CheckValid` tells you which fields to trust. `compositeCheckValid`
 * covers document number, date of birth, and expiry (and, for a TD3
 * passport, the personal-number field) all at once. Not PCI-DSS
 * cardholder data -- it's a different compliance surface (PII, not
 * payment data).
 */
export interface MrzResult {
  readonly documentNumber: string;
  readonly nationality: string;
  readonly dateOfBirth: string; // YYMMDD
  readonly expiryDate: string; // YYMMDD
  readonly sex: string; // 'M', 'F', or '<' (unspecified)
  readonly surname: string;
  readonly givenNames: string;
  readonly documentNumberCheckValid: boolean;
  readonly dateOfBirthCheckValid: boolean;
  readonly expiryCheckValid: boolean;
  readonly compositeCheckValid: boolean;
}

function isMrzChar(code: number): boolean {
  return (code >= A && code <= Z) || (code >= ZERO && code <= NINE) || code === 0x3c; // '<'
}

/** ICAO 9303 character value: digits are themselves, A-Z are 10-35, '<' is 0. */
function mrzCharValue(code: number): number {
  if (code >= ZERO && code <= NINE) return code - ZERO;
  if (code >= A && code <= Z) return code - A + 10;
  return 0; // '<' -- anything else is already excluded by isMrzChar.
}

/** ICAO 9303 check digit: weights 7, 3, 1 repeating, values summed mod 10. */
function mrzCheckDigit(field: string): number {
  const weights = [7, 3, 1];
  let sum = 0;
  for (let i = 0; i < field.length; i++) {
    sum += mrzCharValue(field.charCodeAt(i)) * weights[i % 3];
  }
  return sum % 10;
}

function checkDigitMatches(field: string, digit: string): boolean {
  const code = digit.charCodeAt(0);
  return code >= ZERO && code <= NINE && mrzCheckDigit(field) === code - ZERO;
}

/**
 * Repairs a 7-char date-plus-check-digit span in one shot, so the check
 * digit itself is also OCR-repaired. Falls back to the raw 7 characters
 * unrepaired when any character can't be repaired to a digit.
 */
function repairDateAndCheckField(raw7: string): string {
  let out = '';
  for (let i = 0; i < 7; i++) {
    const r = repairCharCode(raw7.charCodeAt(i));
    if (r < ZERO || r > NINE) return raw7;
    out += String.fromCharCode(r);
  }
  return out;
}

function stripFill(field: string): string {
  return field.replace(/</g, '');
}

/** Splits "SURNAME<<GIVEN<NAMES<<<<" into (surname, given names). */
function parseNameField(field: string): { surname: string; givenNames: string } {
  const sep = field.indexOf('<<');
  const surnamePart = sep < 0 ? field : field.slice(0, sep);
  const givenPart = sep < 0 ? '' : field.slice(sep + 2);
  const render = (part: string) => part.replace(/</g, ' ').replace(/ +$/, '');
  return { surname: render(surnamePart), givenNames: render(givenPart) };
}

/**
 * Scans for `count` consecutive lines of exactly `width` MRZ-alphabet
 * characters, trimmed of surrounding whitespace. Null if none found.
 */
function findMrzLines(rawOcr: string, width: number, count: number): string[] | null {
  const lines = rawOcr.split('\n').map((l) => l.trim());
  for (let start = 0; start + count <= lines.length; start++) {
    let allMatch = true;
    for (let i = 0; i < count && allMatch; i++) {
      const l = lines[start + i];
      if (l.length !== width) {
        allMatch = false;
        continue;
      }
      for (let j = 0; j < l.length; j++) {
        if (!isMrzChar(l.charCodeAt(j))) {
          allMatch = false;
          break;
        }
      }
    }
    if (allMatch) return lines.slice(start, start + count);
  }
  return null;
}

function parseTd3(lines: string[]): MrzResult {
  const l2 = lines[1];
  const { surname, givenNames } = parseNameField(lines[0].slice(5));

  const docNumField = l2.slice(0, 9);
  const documentNumber = stripFill(docNumField);
  const documentNumberCheckValid = checkDigitMatches(docNumField, l2[9]);
  const nationality = l2.slice(10, 13);

  const dob7 = repairDateAndCheckField(l2.slice(13, 20));
  const dateOfBirthCheckValid = checkDigitMatches(dob7.slice(0, 6), dob7[6]);
  const sex = l2[20];

  const exp7 = repairDateAndCheckField(l2.slice(21, 28));
  const expiryCheckValid = checkDigitMatches(exp7.slice(0, 6), exp7[6]);

  const composite = l2.slice(0, 10) + dob7 + exp7 + l2.slice(28, 43);
  const compositeCheckValid = checkDigitMatches(composite, l2[43]);

  return {
    documentNumber, nationality, dateOfBirth: dob7.slice(0, 6), expiryDate: exp7.slice(0, 6),
    sex, surname, givenNames, documentNumberCheckValid, dateOfBirthCheckValid, expiryCheckValid,
    compositeCheckValid,
  };
}

function parseTd1(lines: string[]): MrzResult {
  const l1 = lines[0];
  const l2 = lines[1];
  const { surname, givenNames } = parseNameField(lines[2]);

  const docNumField = l1.slice(5, 14);
  const documentNumber = stripFill(docNumField);
  const documentNumberCheckValid = checkDigitMatches(docNumField, l1[14]);

  const dob7 = repairDateAndCheckField(l2.slice(0, 7));
  const dateOfBirthCheckValid = checkDigitMatches(dob7.slice(0, 6), dob7[6]);
  const sex = l2[7];

  const exp7 = repairDateAndCheckField(l2.slice(8, 15));
  const expiryCheckValid = checkDigitMatches(exp7.slice(0, 6), exp7[6]);
  const nationality = l2.slice(15, 18);

  const composite = l1.slice(5, 30) + dob7 + exp7 + l2.slice(18, 29);
  const compositeCheckValid = checkDigitMatches(composite, l2[29]);

  return {
    documentNumber, nationality, dateOfBirth: dob7.slice(0, 6), expiryDate: exp7.slice(0, 6),
    sex, surname, givenNames, documentNumberCheckValid, dateOfBirthCheckValid, expiryCheckValid,
    compositeCheckValid,
  };
}

/**
 * Scans raw OCR text for a TD3 (passport, 2 lines x 44 chars) or TD1 (ID
 * card, 3 lines x 30 chars) MRZ block and parses it. TD3 is tried first --
 * the two widths never collide. Returns null only when no MRZ-shaped line
 * run is found at all; when one is found but its check digits fail, the
 * fields are still returned with their `*CheckValid` flags false.
 */
export function extractMrz(rawOcr: string): MrzResult | null {
  const td3 = findMrzLines(rawOcr, 44, 2);
  if (td3) return parseTd3(td3);
  const td1 = findMrzLines(rawOcr, 30, 3);
  if (td1) return parseTd1(td1);
  return null;
}
