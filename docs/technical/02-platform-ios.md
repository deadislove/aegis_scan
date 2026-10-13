# Platform: iOS

Source: `ios/Sources/AegisScan/` (Swift package target `AegisScan`, see root
`Package.swift`). Sample: `sample/ios/` (SwiftPM `CoreDemo` runs today;
`CameraApp/` is source for an Xcode app — see its README).

## `CameraScannerPipeline`

`CameraScannerPipeline.swift` is an `AVCaptureVideoDataOutputSampleBufferDelegate`
built on four queues:

| Queue | Kind | Work |
|---|---|---|
| `com.aegisscan.sdk.session` | serial | session configuration, start/stop |
| `com.aegisscan.sdk.processing` | serial, `.userInitiated` | sample-buffer delegate, the back-pressure gate, core logic (Luhn, matcher) |
| `com.aegisscan.sdk.inference` | concurrent, `.userInitiated` | `VNRecognizeTextRequest` and `VNDetectBarcodesRequest`, run in parallel on the same `CVPixelBuffer` |
| main | serial | delegate callbacks |

**Back-pressure**: `isProcessingFrame` (`AtomicFlag`) is taken in
`process(pixelBuffer:)` before any work starts; if it's already held, the
frame is dropped at O(1) cost. `videoOutput.alwaysDiscardsLateVideoFrames =
true` means AVFoundation itself never queues more than one frame on top of
that. Session preset is `.hd1920x1080` on the back wide-angle camera, capped
to 30 FPS (`activeVideoMinFrameDuration`), with continuous autofocus
restricted to `.near` (cards are held close to the lens).

**Frame validation**: `CameraScannerPipeline.validate(_:)` runs the C core's
`aegis_analyze_luma` directly on the pixel buffer's locked base address (zero
copy) before any Vision request — see `FrameRejectionReason` in
`ScannerTypes.swift` for the full list, which mirrors the core's
`FrameStatus`.

**Both Vision requests run concurrently** on the *same* `CVPixelBuffer` via
`DispatchGroup`; the buffer reference is dropped the instant both finish
(`group.notify`), so it returns to AVFoundation's capture pool as early as
possible rather than being held for the whole analysis.

**Card confirmation**: `processCards` requires the same PAN to be extracted
in `ScannerConfiguration.requiredConsecutiveCardHits` (default 2) consecutive
*analyzed* frames before calling the delegate — this is what filters out a
one-off OCR misread flipping a single digit.

## `SecurePAN`

A manually allocated, heap-pinned `UnsafeMutablePointer<UInt8>` buffer —
never a Swift `String`, which could be copied, interned, or retained by the
bridge to Objective-C. `description`/`debugDescription`/`customMirror` are
all redacted to `****1234`, and it isn't `Codable`, so it can't accidentally
end up in `UserDefaults` or a JSON log. `wipe()` calls `aegis_secure_zero`
(not a plain loop, so the optimizer can't elide it); the pipeline calls it
immediately after the delegate/closure callback returns, and `deinit` calls
it again as a safety net.

`expiry: ExpiryDate?` and `cardholderName: String?` are plain, unwiped
properties — best-effort, scanned from the same OCR text as the PAN but not
backed by a checksum, and not PCI-DSS cardholder data on their own.
`CameraScannerPipeline.processCards` populates both automatically via an
internal `extractCardDetails` helper; direct callers get them from
`FintechCore.extractExpiry`/`extractCardholderName` below.

## `FintechCore` facade

```swift
FintechCore.version -> String
FintechCore.validateLuhn(_ digits: String) -> Bool
FintechCore.extractPAN(from rawOCR: String) -> SecurePAN?
FintechCore.extractExpiry(from rawOCR: String) -> ExpiryDate?
FintechCore.extractCardholderName(from rawOCR: String) -> String?
FintechCore.extractMRZ(from rawOCR: String) -> MrzResult?
```

Thin wrappers over the C ABI (`aegis_luhn_validate`, `aegis_extract_pan`,
`aegis_extract_expiry`, `aegis_extract_cardholder_name`,
`aegis_extract_mrz` — the last three are UTF-8 only; there's no UTF-16
native entry point for them). `MrzResult` (in `Mrz.swift`) parses a TD1/
TD3 machine-readable zone (passport/ID card back) per ICAO Doc 9303 —
see [Core Engine](01-core-engine.md)'s MRZ section for the check-digit
story. It's PII, not PCI-DSS cardholder data, and is a standalone call —
`CameraScannerPipeline` does not call it automatically the way it does
`extractExpiry`/`extractCardholderName`, since MRZ scanning is a
different document-capture flow than card scanning.
`VoucherMatcher` (same file, `FintechCore.swift`) owns an
`aegis_matcher_t*` handle, freed in `deinit`; it is **not** safe for
concurrent use, which is why the pipeline keeps exactly one instance per
processing queue (always serial).

## `ScannerConfiguration`

All fields are `var`s with sensible defaults — `detectsCards`,
`detectsVouchers`, `orientation` (`.right` for the back camera in
portrait), `requiredConsecutiveCardHits`, `regionOfInterest`,
`minimumTextHeight`, `symbologies` (defaults to QR/Code128/EAN13/PDF417/
DataMatrix/ITF14), `reportsFrameRejections`.

## Errors

`ScannerError` (camera permission denied/restricted, camera unavailable,
configuration failed, session runtime error, frame rejected) — none of its
cases carry PAN data, so it's safe to log directly.

## See also

- [Core Engine](01-core-engine.md) for the algorithms `FintechCore` wraps.
- [Security & PCI-DSS](07-security-pci-dss.md) for the full wipe story.
- `sample/ios/README.md` for how to run `CoreDemo` today and wire
  `CameraApp/` into a real Xcode project.
