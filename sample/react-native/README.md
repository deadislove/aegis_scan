# AegisScan React Native Sample

A minimal RN app wrapping the real native SDKs: `<AegisScanCameraView />`
full-screen with a status overlay showing brand/last4/expiry/cardholderName
on a detected card, mirroring every other platform sample's shape
(`sample/ios/CameraApp`, `sample/android`, `sample/web/main.js`).

Classic bridge, bare RN CLI (not Expo) -- see
`spec/sample/react-native.md` for why those were chosen over Turbo Native
Modules / Expo.

## What's real here

- **iOS** (`ios/AegisScanRNBridge/`): a CocoaPods pod whose Swift module
  (`AegisScanCamera.swift`) directly wraps `CameraScannerPipeline` from the
  **real** `AegisScan` SwiftPM package at the repo root -- bridged in via
  React Native's own `spm_dependency` CocoaPods helper (RN 0.87+), which
  adds the package as a local `XCLocalSwiftPackageReference`. Not a copy,
  not a stub -- the exact same Swift source the native iOS sample and
  tests use. `AegisScanCameraView.swift` is the camera preview (an
  `AVCaptureVideoPreviewLayer`, same relationship to the pipeline's
  session as `sample/ios/CameraApp/ContentView.swift`'s
  `CameraPreviewView`).
- **Android** (`android/aegisscan-rn-bridge/`): a Gradle library module
  whose Kotlin classes directly call `AndroidCameraScannerEngine` from the
  real `aegisscan-camera`/`aegisscan-core` modules -- consumed via
  `mavenLocal()` (see "Why mavenLocal, not includeBuild" below), not a
  copy.
- **JS** (`src/AegisScanCamera.tsx`): thin typed wrapper -- `start()`/
  `stop()`, `onCardDetected`/`onVouchersMatched`/`onScanError` listeners,
  `<AegisScanCameraView />`.

PCI-DSS: both native modules' event payload for a detected card carries
only `brand`/`last4`/`expiry`/`cardholderName` -- the redacted shape,
never raw PAN digits, same rule as every other platform binding in this
SDK.

## Why mavenLocal, not includeBuild

The first attempt used a Gradle composite build
(`includeBuild('../../../android')` + `dependencySubstitution`) to pull in
`aegisscan-core`/`aegisscan-camera` directly from source, mirroring the
iOS side's SPM approach. That failed with an AGP-internal
`ClassCastException` (`DefaultAndroidLibrarySourceSet_Decorated cannot be
cast to AndroidLibrarySourceSet`) -- the root `android/` project pins
Gradle 8.11.1 + AGP 8.7.3, this RN project resolves Gradle 9.4.1 + a newer
AGP, and a composite build runs *everything* under one Gradle version,
so two AGP versions' copies of the same internal class ended up loaded at
once.

The fix: `aegisscan-core`/`aegisscan-camera` now have a `maven-publish`
setup (`group = "com.aegisscan"`, explicit `version`) and get published to
`~/.m2` with the *root* project's own (already-verified) Gradle 8.11.1 /
AGP 8.7.3 toolchain:

```bash
cd android && ./gradlew :aegisscan-core:publishToMavenLocal :aegisscan-camera:publishToMavenLocal
```

`aegisscan-rn-bridge` then depends on them as plain
`"com.aegisscan:aegisscan-core:1.0.0"` coordinates resolved via
`mavenLocal()` -- fully decoupling the two Gradle/AGP toolchains. Re-run
the publish command after any change to `aegisscan-core`/`aegisscan-camera`
that this sample needs to pick up; there's no file-watching, it's a
manual step.

## Build & run

```bash
npm install

# One-time (and after any aegisscan-core/aegisscan-camera change):
cd ../../android && ./gradlew :aegisscan-core:publishToMavenLocal :aegisscan-camera:publishToMavenLocal && cd -

# iOS
cd ios && pod install && cd ..
npx react-native run-ios      # or open the .xcworkspace in Xcode

# Android
npx react-native run-android
```

Verified in this environment: both
`xcodebuild -workspace ios/AegisScanRNSample.xcworkspace -scheme AegisScanRNSample -sdk iphonesimulator build`
and `cd android && ./gradlew :app:assembleDebug` succeed, producing a real
`.app` / `.apk` linked against the actual AegisScan SDK. Neither was run
on a simulator/emulator/device with a real camera in this session -- that
part is yours to confirm, same caveat as every other platform's sample in
this repo (see `spec/sample/README.md`).
