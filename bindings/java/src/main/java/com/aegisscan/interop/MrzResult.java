// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop;

/**
 * Parsed TD1 (3-line, ID card) or TD3 (2-line, passport) MRZ.
 *
 * <p>Unlike {@code ExpiryDate}/cardholder name, every field here is backed
 * by an ICAO 9303 check digit (same mental model as Luhn backing a PAN) --
 * the {@code *CheckValid} fields tell you which fields to trust.
 * {@code compositeCheckValid} covers document number, date of birth, and
 * expiry (and, for a TD3 passport, the personal-number field) all at once,
 * the way one Luhn digit backs a whole PAN. Not PCI-DSS cardholder data --
 * it's a different compliance surface (PII, not payment data).
 */
public record MrzResult(
    String documentNumber,
    String nationality,
    String dateOfBirth, // YYMMDD
    String expiryDate, // YYMMDD
    char sex, // 'M', 'F', or '<' (unspecified)
    String surname,
    String givenNames,
    boolean documentNumberCheckValid,
    boolean dateOfBirthCheckValid,
    boolean expiryCheckValid,
    boolean compositeCheckValid) {}
