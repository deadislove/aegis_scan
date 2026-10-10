# AegisScan Android Sample

A minimal Android app: a CameraX `PreviewView` plus
`AndroidCameraScannerEngine` (`android/aegisscan-camera`) bound to the same
activity lifecycle. Scanning a card shows its brand and last 4 digits;
scanning a voucher barcode near some text shows the match.

## How it's wired in

This app lives under `sample/android/` (next to the other platforms'
samples) but builds as part of the existing Gradle project in `android/`,
via a custom `projectDir` in `android/settings.gradle.kts`:

```kotlin
include(":sample-android")
project(":sample-android").projectDir = file("../sample/android")
```

That's why its Gradle commands are run from `android/`, not from here.

## Build & install

```bash
cd ../../android
./gradlew :sample-android:assembleDebug
# APK: sample/android/build/outputs/apk/debug/sample-android-debug.apk

# Or straight to a connected device/emulator:
./gradlew :sample-android:installDebug
```

This was built and verified against a real device toolchain in this repo
(Android SDK via `android/local.properties`, JDK 17 via the Foojay toolchain
resolver in `settings.gradle.kts`) — see the root `README.md` for that setup
if `./gradlew` can't find an SDK/JDK on your machine.

## What to look at

- `MainActivity.kt`: requests `CAMERA` permission, builds a CameraX
  `Preview` use case, and passes it into
  `AndroidCameraScannerEngine.start(preview)` so the preview and the
  analyzer share one camera session.
- The `listener` lambda receives `ScanEvent.CardDetected` /
  `VouchersMatched` / `Error` on the main thread. `event.pan` (for a
  detected card) is wiped by the engine immediately after this lambda
  returns — read `brand`/`last4` synchronously, as shown; never store the
  `SecurePan` itself.
- `engine.shutdown()` isn't called explicitly — the engine registers its own
  `DefaultLifecycleObserver` and shuts down on `ON_DESTROY` automatically.

## PCI-DSS note

This sample only ever displays `pan.brand` and `pan.last4` (safe to show per
PCI-DSS 3.4 truncation rules). A real integration would replace that
`statusText.text = ...` line with a synchronous call to your tokenizer or
HSM, still inside the listener callback, still never logging or persisting
the full PAN.
