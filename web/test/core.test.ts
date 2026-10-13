// Copyright 2026 AegisScan Authors. All rights reserved.
// Shared vectors with the C++ / Kotlin / Swift suites.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  CardBrand, detectBrand, extractCard, extractCardDetails, extractCardholderName, extractExpiry,
  repairAndExtract, validate,
} from '../src/core/luhn.js';
import { extractMrz } from '../src/core/mrz.js';
import { SpatialMatcher } from '../src/core/spatialMatcher.js';

const box = (x: number, y: number, width: number, height: number) => ({ x, y, width, height });

describe('LuhnValidator', () => {
  it('validates known PANs', () => {
    for (const pan of ['4111111111111111', '4222222222222', '5555555555554444', '2223003122003222',
      '378282246310005', '3530111333300000']) assert.ok(validate(pan), pan.slice(0, 4));
    assert.equal(validate('4111111111111112'), false);
    assert.equal(validate(''), false);
    assert.equal(validate('4111 1111 1111 1111'), false);
  });
  it('detects brands', () => {
    assert.equal(detectBrand('371449635398431'), CardBrand.Amex);
    assert.equal(detectBrand('2720990000000007'), CardBrand.Mastercard);
    assert.equal(detectBrand('2721000000000000'), CardBrand.Unknown);
    assert.equal(detectBrand('3530111333300000'), CardBrand.Jcb);
  });
  it('repairs OCR confusions (8/B, 0/O/D/Q, 1/l/I/|, 5/S/$, 2/Z, 4/A)', () => {
    assert.equal(repairAndExtract('4012 B888 88B8 1881'), '4012888888881881');
    assert.equal(repairAndExtract('51O5 1D51 O51Q 51OO'), '5105105105105100');
    assert.equal(repairAndExtract('4lI1 |111 1i11 1111'), '4111111111111111');
    assert.equal(repairAndExtract('$555 5555 5555 4444'), '5555555555554444');
    assert.equal(repairAndExtract('Z223 0031 Z200 3222'), '2223003122003222');
    assert.equal(repairAndExtract('A111 1111 1111 1111'), '4111111111111111');
  });
  it('extracts from noisy text', () => {
    assert.equal(repairAndExtract('BANK\nCard No: 4111 1111 1111 1111\nVALID 12/28'), '4111111111111111');
    assert.equal(repairAndExtract('Ref 1234 4111 1111 1111 1111'), '4111111111111111');
    assert.equal(repairAndExtract('4111 1111 1111 1111 123'), '4111111111111111');
    assert.equal(repairAndExtract('994111111111111111199'), '4111111111111111');
  });
  it('rejects non-cards', () => {
    for (const s of ['', 'HELLO WORLD', '4111 1111 1111 1112', '6011 1111 1111 1117', '4111 1111 1111',
      '**** **** **** 1111', 'SOLO BOSS SOLO BOSS', '4111 1111 EXP 1111 1111', '4111 1111\n1111 1111',
      '1'.repeat(100_000)]) assert.equal(repairAndExtract(s), null, `len=${s.length}`);
  });
  it('wipes extracted PAN buffers', () => {
    const pan = extractCard('3782 822463 10005');
    assert.ok(pan);
    assert.equal(pan.last4, '0005');
    pan.wipe();
    assert.ok(pan.digits.every((d) => d === 0));
  });
  it('extracts expiry dates', () => {
    const expiry = extractExpiry('VALID THRU 12/29');
    assert.equal(expiry?.month, 12);
    assert.equal(expiry?.year, 2029);
    assert.equal(extractExpiry('4111111111111111'), null);
    // Invalid month is skipped, not returned.
    assert.equal(extractExpiry('13/45'), null);
  });
  it('extracts cardholder names', () => {
    assert.equal(extractCardholderName('BANK OF AEGIS\n4111111111111111\nJOHN DOE'), 'JOHN DOE');
    // Brand/product words are filtered; plain digits have no candidate line.
    assert.equal(extractCardholderName('VISA PLATINUM\n4111111111111111'), null);
  });
  it('attaches expiry and name to the extracted PAN', () => {
    const pan = extractCardDetails('BANK OF AEGIS\n4111 1111 1111 1111\nVALID THRU 12/29\nJOHN DOE');
    assert.ok(pan);
    assert.equal(String.fromCharCode(...pan.digits), '4111111111111111');
    assert.equal(pan.expiry?.month, 12);
    assert.equal(pan.expiry?.year, 2029);
    assert.equal(pan.cardholderName, 'JOHN DOE');
  });
});

describe('Mrz', () => {
  // ICAO Doc 9303 worked example ("ERIKSSON ANNA MARIA"), the standard
  // specimen used industry-wide for this purpose, not a real document.
  it('parses a valid TD3 passport specimen', () => {
    const mrz = extractMrz(
      'P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\n'
      + 'L898902C36UTO7408122F1204159ZE184226B<<<<<10');
    assert.ok(mrz);
    assert.equal(mrz.documentNumber, 'L898902C3');
    assert.equal(mrz.nationality, 'UTO');
    assert.equal(mrz.dateOfBirth, '740812');
    assert.equal(mrz.expiryDate, '120415');
    assert.equal(mrz.sex, 'F');
    assert.equal(mrz.surname, 'ERIKSSON');
    assert.equal(mrz.givenNames, 'ANNA MARIA');
    assert.ok(mrz.documentNumberCheckValid);
    assert.ok(mrz.dateOfBirthCheckValid);
    assert.ok(mrz.expiryCheckValid);
    assert.ok(mrz.compositeCheckValid);
  });
  it('detects a corrupted TD3 composite check digit', () => {
    const mrz = extractMrz(
      'P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\n'
      + 'L898902C36UTO7408122F1204159ZE184226B<<<<<11');
    assert.ok(mrz);
    assert.ok(mrz.documentNumberCheckValid); // Unaffected field.
    assert.equal(mrz.compositeCheckValid, false);
  });
  it('parses a valid TD1 ID card specimen and repairs OCR confusion', () => {
    const clean = extractMrz(
      'I<UTOAB12345671<<<<<<<<<<<<<<<\n'
      + '7408122F3001019UTO<<<<<<<<<<<4\n'
      + 'ERIKSSON<<ANNA<MARIA<<<<<<<<<<');
    assert.ok(clean);
    assert.equal(clean.documentNumber, 'AB1234567');
    assert.equal(clean.dateOfBirth, '740812');
    assert.equal(clean.expiryDate, '300101');
    assert.ok(clean.compositeCheckValid);

    // '0' misread as 'O' in both date fields; check digits still computed
    // against the repaired digits, same contract as extractExpiry.
    const confused = extractMrz(
      'I<UTOAB12345671<<<<<<<<<<<<<<<\n'
      + '74O8122F3OO1O19UTO<<<<<<<<<<<4\n'
      + 'ERIKSSON<<ANNA<MARIA<<<<<<<<<<');
    assert.ok(confused);
    assert.equal(confused.dateOfBirth, '740812');
    assert.equal(confused.expiryDate, '300101');
    assert.ok(confused.dateOfBirthCheckValid);
    assert.ok(confused.expiryCheckValid);
  });
  it('rejects text with no MRZ-shaped lines', () => {
    assert.equal(extractMrz('JUST SOME ORDINARY OCR TEXT\nNOT AN MRZ AT ALL'), null);
    assert.equal(extractMrz(''), null);
  });
});

describe('SpatialMatcher', () => {
  it('matches text below barcode', () => {
    const r = new SpatialMatcher().matchBarcodesWithText(
      [{ payload: 'VCH-12345678', box: box(100, 100, 300, 80) }],
      [{ text: 'Gift Voucher', box: box(100, 20, 200, 30) }, { text: 'VCH 1234 5678', box: box(110, 190, 280, 30) },
        { text: '12345678', box: box(900, 900, 100, 30) }]);
    assert.equal(r.length, 1);
    assert.equal(r[0].associatedText, 'VCH 1234 5678');
    assert.equal(r[0].alignment, 'text-below');
  });
  it('prefers vertical alignment over a closer side-by-side label', () => {
    const r = new SpatialMatcher().matchBarcodesWithText(
      [{ payload: '9876543210', box: box(100, 100, 200, 60) }],
      [{ text: '9876543210', box: box(300, 115, 40, 30) }, { text: '98765 43210', box: box(100, 250, 200, 30) }]);
    assert.equal(r[0].associatedText, '98765 43210');
  });
  it('requires overlap, folds OCR confusions, ignores invalid boxes', () => {
    const m = new SpatialMatcher();
    const bc = [{ payload: 'AB1058', box: box(0, 0, 200, 60) }];
    assert.equal(m.matchBarcodesWithText(bc, [{ text: 'TOTAL DUE', box: box(0, 70, 200, 20) }]).length, 0);
    assert.equal(m.matchBarcodesWithText(bc, [{ text: 'A8 IO5B', box: box(0, 70, 200, 20) }])[0].associatedText, 'A8 IO5B');
    assert.equal(m.matchBarcodesWithText([{ payload: '12345', box: box(NaN, 0, 10, 10) }],
      [{ text: '12345', box: box(0, 12, 10, 10) }]).length, 0);
  });
});
