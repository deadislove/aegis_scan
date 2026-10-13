# AegisScan iOS Sample

Two parts, because camera capture needs a real iOS app target (Xcode) while
the core logic doesn't:

| Directory | What it is | How to run it |
|---|---|---|
| `CoreDemo/` | A SwiftPM executable exercising `FintechCore` (Luhn, OCR repair/extraction) and `VoucherMatcher` -- the same API `CameraScannerPipeline` calls on every frame | `cd CoreDemo && swift run` -- works right now, no Xcode project needed |
| `CameraApp/` | `ContentView.swift` + `AegisScanCameraApp.swift`: a SwiftUI screen wrapping `CameraScannerPipeline` with a live preview | Copy into a new Xcode App project -- steps below |

This repo has no `xcodegen`/`tuist` set up and no generated `.xcodeproj`, so
`CameraApp/` is source you drop into a project you create, not something
you can `swift run`. `CoreDemo/` is the part that's actually verified to
build and run in this environment (see below).

## CoreDemo (run now)

```bash
cd CoreDemo
swift run
```

Expected output (abridged):

```
AegisScan core version: 1.0.0
validateLuhn("4111111111111111") = true
extractPAN found: SecurePAN(visa, ****1111) (brand=visa, length=16)
extractExpiry found: 12/2029
extractCardholderName found: JOHN DOE
VoucherMatcher.match: 1 match(es)
  barcode "099123456789" <-> text "VOUCHER 099123456789" (distance=0.049752526)
```

`CoreDemo/Package.swift` depends on the root package via a local path
(`.package(path: "../../..")`), so it always builds against the SDK source
in this repo, not a published version.

## CameraApp (needs Xcode)

1. In Xcode: **File > New > Project > iOS > App**, SwiftUI interface, any
   name (e.g. `AegisScanDemo`), iOS 15+ deployment target.
2. **File > Add Package Dependencies > Add Local...**, select the repo root
   (the folder containing `Package.swift`). This adds the `AegisScan`
   library product to your new app target.
3. Delete the template's `ContentView.swift`, then drag
   `CameraApp/ContentView.swift` and `CameraApp/AegisScanCameraApp.swift`
   into your project (replace the template's `@main` file too).
4. In your target's **Info** tab, add **Privacy - Camera Usage Description**
   (`NSCameraUsageDescription`) with a user-facing string, e.g. "Used to
   scan payment cards and vouchers." Without this key iOS kills the app the
   moment `CameraScannerPipeline.start()` requests access.
5. Run on a real device (the simulator has no camera, so
   `AVCaptureDevice.default(.builtInWideAngleCamera, ...)` returns `nil` and
   the pipeline reports `.cameraUnavailable`).

## What to look at in CameraApp

- `ScannerViewModel` implements `CameraScannerPipelineDelegate` and bridges
  its main-queue callbacks into `@Published` SwiftUI state.
- `didDetectCard pan:` reads `pan.brand`/`pan.last4`/`pan.expiry`/
  `pan.cardholderName` *synchronously, inside the callback* — the pipeline
  wipes `pan` right after this method returns. A real integration sends the
  digits to a tokenizer/HSM here instead of just displaying them. (`expiry`/
  `cardholderName` are best-effort, attached automatically by the pipeline;
  no checksum backs them the way Luhn backs the PAN.)
- `CameraPreviewView` is the standard `UIViewRepresentable` shim for
  attaching an `AVCaptureVideoPreviewLayer` to the pipeline's
  `session` (`CameraScannerPipeline.session`), so the preview and the
  analyzer share one `AVCaptureSession`.
