# Quickstart: iOS

Full technical detail: `docs/technical/02-platform-ios.md`. Runnable sample:
`sample/ios/` (see its `README.md` — `CoreDemo` runs today via `swift run`;
`CameraApp/` is source for a real Xcode app).

## 1. Add the package

In Xcode: **File > Add Package Dependencies > Add Local...**, select this
repo's root (the folder with `Package.swift`). This adds the `AegisScan`
library product.

## 2. Add a camera usage string

Target's **Info** tab → **Privacy - Camera Usage Description**
(`NSCameraUsageDescription`). Without this, iOS kills the app the moment
you call `start()`.

## 3. Show a preview and scan

```swift
import AegisScan
import AVFoundation
import SwiftUI

final class ScannerViewModel: NSObject, ObservableObject, CameraScannerPipelineDelegate {
    let pipeline = CameraScannerPipeline()
    @Published var lastCard: String = ""

    override init() {
        super.init()
        pipeline.delegate = self
    }

    func scanner(_ scanner: CameraScannerPipeline, didDetectCard pan: SecurePAN) {
        // pan is wiped the instant this method returns -- do your
        // tokenizer/HSM call here, synchronously, not after.
        lastCard = "\(pan.brand) ****\(pan.last4)"
    }
}
```

Attach `viewModel.pipeline.session` to an `AVCaptureVideoPreviewLayer`
(see `sample/ios/CameraApp/ContentView.swift` for the full
`UIViewRepresentable`), then call `viewModel.pipeline.start()` /
`.stop()` from `onAppear`/`onDisappear`.

## 4. Without a camera (text you already have)

```swift
if let pan = FintechCore.extractPAN(from: ocrText) {
    print(pan)       // redacted: SecurePAN(visa, ****1234)
    pan.wipe()        // or let it fall out of scope / rely on deinit
}
```

## Tuning

`pipeline.configuration` (`ScannerConfiguration`) — toggle
`detectsCards`/`detectsVouchers`, change `requiredConsecutiveCardHits`
(default 2; lower it for faster-but-noisier detection), or restrict
`regionOfInterest` if cards are only ever shown in part of the frame.

## Must-not-do

- Don't store a `SecurePAN` past the delegate call that handed it to you.
- Don't run on the simulator expecting a real scan — there's no camera, so
  the pipeline reports `.cameraUnavailable`. Use `FintechCore.extractPAN`
  directly (step 4) for simulator-only testing.
