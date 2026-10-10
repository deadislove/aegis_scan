# AegisScan Camera Harness (macOS)

A small native Mac app: opens the MacBook's built-in camera, runs Apple
Vision (`VNRecognizeTextRequest` + `VNDetectBarcodesRequest`) on each frame,
and calls the exact same Swift API — `FintechCore.extractPAN`,
`VoucherMatcher` — that `CameraScannerPipeline` calls on a real iPhone (see
`ios/Sources/AegisScan/CameraScannerPipeline.swift`).

## Why this exists, and why it isn't `CameraScannerPipeline`

`CameraScannerPipeline` only ever requests a `position: .back` camera —
correct for an iPhone, but that position doesn't exist on a Mac, so it can
never find a device here. Rather than bend the real (verified-on-iPhone)
SDK class to also handle a Mac's camera, `CaptureEngine.swift` in this
sample is new, Mac-only AVFoundation + Vision glue, written specifically
to call into the same card/voucher logic a real iOS build exercises. It
intentionally skips the production pipeline's extras (frame back-pressure
tuned for 30fps live scanning, multi-frame confirmation) since this is a
manual test tool for a held-still printed test card, not a shipping
camera pipeline — see `docs/technical/02-platform-ios.md` for what the
real pipeline does with all of that.

## Run it

```bash
cd sample/macos/CameraHarness
swift run
```

First run: macOS will prompt for camera access, attributed to
"CameraHarness" — this works because `Package.swift` embeds `Info.plist`
(with `NSCameraUsageDescription`) directly into the executable via a
linker section, the standard trick for giving a bare SwiftPM tool the
privacy-usage string it needs (see the comment in `Package.swift`).

A window opens with a live camera preview, a status line, a box showing
the raw recognized text, and a **Copy recognized text** button.

## Using it to test the other samples

Point the camera at the test PAN or test barcode described in
`spec/sample/README.md`. Once the status line shows a `Card:`/`Voucher:`
result, click **Copy recognized text**, then feed that same real,
camera-recognized text into the .NET or Java sample to prove their
Luhn/repair logic against it too:

```bash
cd ../../csharp/AegisScan.Sample.Console && dotnet run -- "$(pbpaste)"
cd ../../java && make run ARGS="$(pbpaste)"
```

See `spec/sample/macos.md` for the full walkthrough.
