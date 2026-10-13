// Copyright 2026 AegisScan Authors. All rights reserved.
namespace AegisScan.Interop;

/// <summary>
/// Parsed TD1 (3-line, ID card) or TD3 (2-line, passport) MRZ.
/// <para>
/// Unlike <c>ExpiryDate</c>/cardholder name, every field here is backed by
/// an ICAO 9303 check digit (same mental model as Luhn backing a PAN) --
/// the <c>*CheckValid</c> properties tell you which fields to trust.
/// <see cref="CompositeCheckValid"/> covers document number, date of
/// birth, and expiry (and, for a TD3 passport, the personal-number field)
/// all at once, the way one Luhn digit backs a whole PAN. Not PCI-DSS
/// cardholder data -- it's a different compliance surface (PII, not
/// payment data).
/// </para>
/// </summary>
public sealed record MrzResult(
    string DocumentNumber,
    string Nationality,
    string DateOfBirth, // YYMMDD
    string ExpiryDate,  // YYMMDD
    char Sex,           // 'M', 'F', or '<' (unspecified)
    string Surname,
    string GivenNames,
    bool DocumentNumberCheckValid,
    bool DateOfBirthCheckValid,
    bool ExpiryCheckValid,
    bool CompositeCheckValid);
