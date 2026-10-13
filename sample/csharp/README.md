# AegisScan .NET Sample

A console app that exercises every entry point in `AegisScan.Interop`
(`bindings/dotnet/AegisScan.Interop`): version check, Luhn validation, OCR
repair + PAN extraction, frame-quality analysis, and barcode/text spatial
matching.

`FintechCoreWrapper` itself is a logic library (no camera) — in a real POS
/ kiosk app you'd wire it into whatever frame source you already have (a
USB/HID scanner feed, a WPF `CaptureElement`, a MAUI `CameraView` plugin).
This sample also includes an optional **live camera mode** (`--camera`,
see below) that opens your Mac's webcam directly, for testing the real
pipeline end to end without needing a separate host app.

## Prerequisites

- .NET 8 SDK
- The native core built once:
  ```bash
  cd ../../core && make lib
  ```
  This produces `core/build/libaegis_core.dylib` (macOS), `.so` (Linux), or
  (cross-compiled) `aegis_core.dll` (Windows). The sample's `.csproj` copies
  whichever one exists next to the built executable automatically.

## Run

```bash
cd AegisScan.Sample.Console
dotnet run
```

Expected output (abridged):

```
AegisScan core version: 1.0.0
ValidateLuhn("4111111111111111") = True
ExtractPan found: SecurePan(Visa, ****1111) (brand=Visa, length=16)
ExtractExpiry found: 12/2029
ExtractCardholderName found: JOHN DOE
AnalyzeLuma: ok=True meanLuma=128.0 focusScore=0.000
MatchBarcodesWithText: 1 match(es)
  barcode "099123456789" <-> text "VOUCHER 099123456789" (distance=0.050, alignment=1)
```

## Live camera mode (`--camera`)

```bash
dotnet run -- --camera
```

Opens this Mac's webcam directly and loops: capture a frame, run OCR, run
barcode decoding, feed the result through `FintechCoreWrapper.ExtractPan`.
Press Ctrl+C to stop.

This does **not** reimplement OCR or barcode decoding in C# — that's
intentionally the job of a platform vision framework (Apple Vision on
iOS, Google ML Kit on Android), and building a competitive one from
scratch would be its own multi-year project. Instead it shells out to
three small, well-known CLI tools that already do this well:

```bash
brew install imagesnap   # grabs a frame from the webcam
brew install tesseract   # OCR (same engine the web sample uses, via WASM there)
brew install zbar        # provides zbarimg, for barcode decoding
```

Every frame's OCR/barcode output is then run through this SDK's own real
`ExtractPan` (repair + Luhn + brand) logic — that's the part this sample
actually demonstrates; the three CLI tools are just how a frame gets
turned into text/payload for a quick console demo, not something you'd
necessarily ship as-is (a real integration would more likely call an SDK
like OpenCvSharp for capture and a proper OCR binding, or just receive
frames from whatever camera API its host app already uses).

## What to copy into your app

- `using SecurePan? pan = FintechCoreWrapper.ExtractPan(ocrText);` — always
  wrap the result in `using`/`Dispose()`. Never call `.UseDigits(...)`
  outside the `using` scope, and never copy the digits into a `string`.
- `FintechCoreWrapper.ExtractCardDetails(ocrText)` runs the PAN, expiry, and
  cardholder-name extraction over one OCR pass and attaches `Expiry`/
  `CardholderName` to the returned `SecurePan` — prefer it over three
  separate calls when you want all three. Expiry/name are best-effort (no
  checksum backs them, unlike the PAN).
- `FintechCoreWrapper` instances own a native matcher handle — create one
  per logical session (e.g. per POS terminal process) and `Dispose()` it
  when you're done, not per-frame.
- Nothing in this SDK touches disk, `ILogger`, `Console.WriteLine` (besides
  this demo), or any serialization attribute — keep it that way in your
  integration so PCI-DSS scope doesn't expand.
