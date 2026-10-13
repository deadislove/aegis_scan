// Copyright 2026 AegisScan Authors. All rights reserved.

import CoreGraphics
import XCTest
@testable import AegisScan

final class FintechCoreTests: XCTestCase {

    private func digits(_ pan: SecurePAN?) -> String? {
        pan?.withDigits { String(decoding: $0, as: UTF8.self) }
    }

    func testLuhn() {
        XCTAssertTrue(FintechCore.validateLuhn("4111111111111111"))
        XCTAssertFalse(FintechCore.validateLuhn("4111111111111112"))
        XCTAssertFalse(FintechCore.validateLuhn(""))
    }

    func testOcrEdgeCases() {
        XCTAssertEqual(digits(FintechCore.extractPAN(from: "4012 B888 88B8 1881")),
                       "4012888888881881")                       // 8 ↔ B
        XCTAssertEqual(digits(FintechCore.extractPAN(from: "51O5 1D51 O51Q 51OO")),
                       "5105105105105100")                       // 0 ↔ O/D/Q
        XCTAssertEqual(digits(FintechCore.extractPAN(from: "4lI1 |111 1i11 1111")),
                       "4111111111111111")                       // 1 ↔ l/I/|
        XCTAssertEqual(digits(FintechCore.extractPAN(from: "$555 5555 5555 4444")),
                       "5555555555554444")                       // 5 ↔ $/S
        XCTAssertNil(FintechCore.extractPAN(from: "SOLO BOSS SOLO BOSS"))
        XCTAssertNil(FintechCore.extractPAN(from: "4111 1111 1111 1112"))
    }

    func testSecurePanIsRedactedAndWipeable() throws {
        let pan = try XCTUnwrap(FintechCore.extractPAN(from: "3782 822463 10005"))
        XCTAssertEqual(pan.brand, .amex)
        XCTAssertEqual(pan.last4, "0005")
        XCTAssertFalse("\(pan)".contains("378282"))
        XCTAssertFalse(String(reflecting: pan).contains("378282"))
        var dumped = ""
        dump(pan, to: &dumped)
        XCTAssertFalse(dumped.contains("378282"))
        pan.wipe()
        XCTAssertTrue(pan.isWiped)
        XCTAssertEqual(pan.withDigits { $0.count }, 0)
    }

    func testExtractExpiry() {
        let expiry = FintechCore.extractExpiry(from: "VALID THRU 12/29")
        XCTAssertEqual(expiry?.month, 12)
        XCTAssertEqual(expiry?.year, 2029)
        XCTAssertNil(FintechCore.extractExpiry(from: "4111111111111111"))
        // Invalid month is skipped, not returned.
        XCTAssertNil(FintechCore.extractExpiry(from: "13/45"))
    }

    func testExtractCardholderName() {
        XCTAssertEqual(
            FintechCore.extractCardholderName(from: "BANK OF AEGIS\n4111111111111111\nJOHN DOE"),
            "JOHN DOE")
        // Brand/product words are filtered; plain digits have no candidate line.
        XCTAssertNil(FintechCore.extractCardholderName(from: "VISA PLATINUM\n4111111111111111"))
    }

    func testExtractMrzParsesValidTd3PassportSpecimen() {
        // ICAO Doc 9303 worked example ("ERIKSSON ANNA MARIA"), the standard
        // specimen used industry-wide for this purpose, not a real document.
        let mrz = FintechCore.extractMRZ(from:
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\n" +
            "L898902C36UTO7408122F1204159ZE184226B<<<<<10")
        XCTAssertEqual(mrz?.documentNumber, "L898902C3")
        XCTAssertEqual(mrz?.nationality, "UTO")
        XCTAssertEqual(mrz?.dateOfBirth, "740812")
        XCTAssertEqual(mrz?.expiryDate, "120415")
        XCTAssertEqual(mrz?.sex, "F")
        XCTAssertEqual(mrz?.surname, "ERIKSSON")
        XCTAssertEqual(mrz?.givenNames, "ANNA MARIA")
        XCTAssertEqual(mrz?.documentNumberCheckValid, true)
        XCTAssertEqual(mrz?.dateOfBirthCheckValid, true)
        XCTAssertEqual(mrz?.expiryCheckValid, true)
        XCTAssertEqual(mrz?.compositeCheckValid, true)
    }

    func testExtractMrzRejectsNonMrzText() {
        XCTAssertNil(FintechCore.extractMRZ(from: "JUST SOME ORDINARY TEXT"))
        XCTAssertNil(FintechCore.extractMRZ(from: ""))
    }

    func testCameraPipelineAttachesExpiryAndNameToConfirmedCard() throws {
        let pan = try XCTUnwrap(FintechCore.extractCardDetails(
            from: "BANK OF AEGIS\n4111 1111 1111 1111\nVALID THRU 12/29\nJOHN DOE"))
        XCTAssertEqual(digits(pan), "4111111111111111")
        XCTAssertEqual(pan.expiry?.month, 12)
        XCTAssertEqual(pan.expiry?.year, 2029)
        XCTAssertEqual(pan.cardholderName, "JOHN DOE")
    }

    func testVoucherMatcherPrefersStackedText() throws {
        let matcher = try XCTUnwrap(VoucherMatcher())
        let barcode = DetectedBarcode(payload: "9876543210", symbology: "code128",
                                      boundingBox: CGRect(x: 100, y: 100, width: 200, height: 60))
        let side = DetectedText(text: "9876543210",
                                boundingBox: CGRect(x: 300, y: 115, width: 40, height: 30))
        let below = DetectedText(text: "98765 43210",
                                 boundingBox: CGRect(x: 100, y: 250, width: 200, height: 30))
        let matches = matcher.match(barcodes: [barcode], texts: [side, below])
        XCTAssertEqual(matches.count, 1)
        XCTAssertEqual(matches.first?.associatedText, "98765 43210")
        XCTAssertTrue(matcher.match(barcodes: [], texts: [below]).isEmpty)
    }

    func testVisionRectConversion() {
        let rect = CameraScannerPipeline.imageRect(
            CGRect(x: 0.1, y: 0.7, width: 0.5, height: 0.2), CGSize(width: 1000, height: 500))
        XCTAssertEqual(rect.minX, 100, accuracy: 0.001)
        XCTAssertEqual(rect.minY, 50, accuracy: 0.001)  // (1 - 0.9) * 500
        XCTAssertEqual(rect.width, 500, accuracy: 0.001)
        XCTAssertEqual(rect.height, 100, accuracy: 0.001)
    }
}
