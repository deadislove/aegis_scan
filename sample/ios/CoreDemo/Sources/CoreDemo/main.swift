// AegisScan core-logic demo. Run with `swift run` from this directory.
//
// This exercises the same Swift facade (`AegisScan.FintechCore`,
// `VoucherMatcher`) that `CameraScannerPipeline` calls on every frame -- see
// ../CameraApp for the camera-facing half, which needs a real iOS app
// target instead of a command-line executable.
import AegisScan
import CoreGraphics

print("AegisScan core version: \(FintechCore.version)")
print()

// 1. Plain Luhn check ---------------------------------------------------
let testPan = "4111111111111111" // Visa test number, passes Luhn.
print("validateLuhn(\"\(testPan)\") = \(FintechCore.validateLuhn(testPan))")
print()

// 2. OCR repair + extraction ---------------------------------------------
// Two characters misread as letters ('I'/'l' for '1'); the core repairs
// them but still requires most digits to already be genuine -- it won't
// "rescue" a mostly-garbled string.
let noisyOcr = "CARD 4111-111I-1111-111l\nEXP 12/29 CVV 123"
if let pan = FintechCore.extractPAN(from: noisyOcr) {
    // Never log this in real code; .description is redacted on purpose but
    // isn't a substitute for just not logging PANs.
    print("extractPAN found: \(pan) (brand=\(pan.brand), length=\(pan.length))")
    pan.withDigits { digits in
        print("  digits available via zero-copy buffer, length=\(digits.count)")
    }
    pan.wipe() // Deterministic; the camera pipeline does this right after its callback returns.
} else {
    print("extractPAN: no PAN found")
}
print()

// 3. Voucher barcode <-> printed text spatial matching ---------------------
guard let matcher = VoucherMatcher(frameSize: CGSize(width: 1080, height: 1920)) else {
    fatalError("VoucherMatcher init failed")
}
let barcodes = [
    DetectedBarcode(payload: "099123456789", symbology: "VNBarcodeSymbologyCode128",
                     boundingBox: CGRect(x: 100, y: 800, width: 300, height: 80)),
]
let texts = [
    DetectedText(text: "THANK YOU FOR SHOPPING", boundingBox: CGRect(x: 80, y: 200, width: 400, height: 40)),
    DetectedText(text: "VOUCHER 099123456789", boundingBox: CGRect(x: 100, y: 720, width: 320, height: 50)),
]
let matches = matcher.match(barcodes: barcodes, texts: texts)
print("VoucherMatcher.match: \(matches.count) match(es)")
for m in matches {
    print("  barcode \"\(m.barcode.payload)\" <-> text \"\(m.associatedText)\" (distance=\(m.normalizedDistance))")
}
