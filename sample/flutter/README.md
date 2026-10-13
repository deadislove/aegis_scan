# AegisScan Flutter Plugin Sample

A minimal plugin + example app wrapping the real native SDKs:
`<AegisScanCameraView />` full-screen with a status overlay showing
brand/last4/expiry/cardholderName on a detected card, mirroring every
other platform sample's shape (`sample/ios/CameraApp`, `sample/android`,
`sample/web/main.js`, `sample/react-native`).

See `spec/sample/flutter.md` for the full build history, including every
real build failure hit getting this working and the exact fix for each.

## What's real here

- **iOS** (`ios/Classes/`): `AegisScanFlutterPlugin.swift` (a
  `FlutterPlugin` + `FlutterStreamHandler` wrapping `CameraScannerPipeline`)
  and `AegisScanCameraPlatformView.swift` (a `FlutterPlatformView` showing
  an `AVCaptureVideoPreviewLayer` on the plugin's shared pipeline session).
  The real `AegisScan` SwiftPM package is bridged in via a hand-rolled
  `XCLocalSwiftPackageReference` in the example app's Podfile
  `post_install` hook (Flutter's CocoaPods integration has no built-in
  helper for this, unlike React Native's `spm_dependency` -- see the spec
  doc for the exact Ruby).
- **Android** (`android/src/main/kotlin/`): `AegisScanFlutterPlugin.kt`
  (a `FlutterPlugin` + `MethodCallHandler` + `EventChannel.StreamHandler` +
  `ActivityAware`, handling the `CAMERA` runtime permission directly) and
  `AegisScanCameraPlatformView.kt` (wrapping `AndroidCameraScannerEngine`).
  Depends on the real `aegisscan-core`/`aegisscan-camera` via
  `mavenLocal()`.
- **Dart** (`lib/`, `example/lib/main.dart`): `startScanning()`/
  `stopScanning()`, a `scanEvents` stream of a sealed `ScanEvent` type, and
  `AegisScanCameraView`.

PCI-DSS: both native plugins' event payload for a detected card carries
only `brand`/`last4`/`expiry`/`cardholderName` -- the redacted shape,
never raw PAN digits, same rule as every other platform binding in this
SDK.

## Build & run

```bash
# One-time (and after any aegisscan-core/aegisscan-camera change):
cd ../../android && ./gradlew :aegisscan-core:publishToMavenLocal :aegisscan-camera:publishToMavenLocal && cd -

flutter pub get
cd example && flutter pub get

# iOS
cd ios && pod install && cd ..
xcodebuild -workspace ios/Runner.xcworkspace -scheme Runner -configuration Debug \
  -destination 'id=<simulator-udid>' build   # `xcrun simctl list devices` for a UDID
# or: open ios/Runner.xcworkspace in Xcode and hit Run.
# Do NOT use `flutter build ios`/`flutter run` for this sample -- see the
# spec doc's "legacy build locations" note for why that specific command
# doesn't work with a local SPM package, even though raw xcodebuild does.

# Android
./gradlew :app:assembleDebug   # from example/android
```

Verified in this environment: both the iOS `xcodebuild` command above and
`./gradlew :app:assembleDebug` succeed, producing a real `.app`/`.apk`
linked against the actual AegisScan SDK. `flutter analyze` is clean.
Neither was run on a simulator/emulator/device with a real camera in this
session -- that part is yours to confirm, same caveat as every other
sample in this repo (see `spec/sample/README.md`).
