// Copyright 2026 AegisScan Authors. All rights reserved.

import CAegisCore
import Foundation

/// Parsed TD1 (3-line, ID card) or TD3 (2-line, passport) MRZ.
///
/// Unlike `ExpiryDate`/cardholder name, every field here is backed by an
/// ICAO 9303 check digit (same mental model as Luhn backing a PAN) --
/// `*CheckValid` tells you which fields to trust. `compositeCheckValid`
/// covers document number, date of birth, and expiry (and, for a TD3
/// passport, the personal-number field) all at once, the way one Luhn
/// digit backs a whole PAN. Not PCI-DSS cardholder data -- it's a
/// different compliance surface (PII, not payment data) -- so, unlike
/// `SecurePAN`, this is a plain, non-wiped struct.
public struct MrzResult: Sendable, Equatable {
    public let documentNumber: String
    public let nationality: String
    public let dateOfBirth: String  // YYMMDD
    public let expiryDate: String   // YYMMDD
    public let sex: Character       // "M", "F", or "<" (unspecified)
    public let surname: String
    public let givenNames: String
    public let documentNumberCheckValid: Bool
    public let dateOfBirthCheckValid: Bool
    public let expiryCheckValid: Bool
    public let compositeCheckValid: Bool
}

extension FintechCore {
    /// Scans for a TD3 (passport, 2 lines x 44 chars) or TD1 (ID card,
    /// 3 lines x 30 chars) MRZ block in raw OCR text. Returns `nil` only
    /// when no MRZ-shaped line run is found at all; when one is found but
    /// its check digits fail, the fields are still returned with their
    /// `*CheckValid` flags false, so a caller can decide whether a
    /// not-quite-matching MRZ (e.g. a damaged document) is still worth
    /// surfacing.
    public static func extractMRZ(from rawOCR: String) -> MrzResult? {
        var text = rawOCR
        return text.withUTF8 { utf8 -> MrzResult? in
            var mrz = aegis_mrz()
            let status = utf8.withMemoryRebound(to: CChar.self) { input in
                aegis_extract_mrz(input.baseAddress, Int32(clamping: input.count), &mrz)
            }
            guard status == AEGIS_OK else { return nil }
            return MrzResult(
                documentNumber: String(cString: withUnsafeBytes(of: mrz.document_number) {
                    $0.baseAddress!.assumingMemoryBound(to: CChar.self)
                }),
                nationality: String(cString: withUnsafeBytes(of: mrz.nationality) {
                    $0.baseAddress!.assumingMemoryBound(to: CChar.self)
                }),
                dateOfBirth: String(cString: withUnsafeBytes(of: mrz.date_of_birth) {
                    $0.baseAddress!.assumingMemoryBound(to: CChar.self)
                }),
                expiryDate: String(cString: withUnsafeBytes(of: mrz.expiry_date) {
                    $0.baseAddress!.assumingMemoryBound(to: CChar.self)
                }),
                sex: Character(UnicodeScalar(UInt8(bitPattern: mrz.sex))),
                surname: String(cString: withUnsafeBytes(of: mrz.surname) {
                    $0.baseAddress!.assumingMemoryBound(to: CChar.self)
                }),
                givenNames: String(cString: withUnsafeBytes(of: mrz.given_names) {
                    $0.baseAddress!.assumingMemoryBound(to: CChar.self)
                }),
                documentNumberCheckValid: mrz.document_number_check_valid != 0,
                dateOfBirthCheckValid: mrz.date_of_birth_check_valid != 0,
                expiryCheckValid: mrz.expiry_check_valid != 0,
                compositeCheckValid: mrz.composite_check_valid != 0)
        }
    }
}
