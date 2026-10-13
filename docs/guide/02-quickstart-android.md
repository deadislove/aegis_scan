# Quickstart: Android

Full technical detail: `docs/technical/03-platform-android.md`. Runnable
sample: `sample/android/` (`cd android && ./gradlew
:sample-android:assembleDebug` — see its `README.md`).

## 1. Add the modules

```kotlin
// settings.gradle.kts
include(":aegisscan-core", ":aegisscan-camera")
```

```kotlin
// your app module's build.gradle.kts
dependencies {
    implementation(project(":aegisscan-camera"))
    implementation(project(":aegisscan-core"))

    // aegisscan-camera declares these as `implementation`, so anything you
    // reference directly (Preview, UseCase) needs its own dependency too:
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
}
```

## 2. Request the CAMERA permission, then scan

```kotlin
val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

val engine = AndroidCameraScannerEngine(
    context = this,
    lifecycleOwner = this,
    config = ScannerConfig(),
) { event ->
    when (event) {
        is ScanEvent.CardDetected -> {
            // event.pan is wiped right after this lambda returns -- do your
            // tokenizer/HSM call here, synchronously. pan.expiry/cardholderName
            // are best-effort (no checksum backs them, unlike the PAN).
            val pan = event.pan
            statusText.text = "${pan.brand} ****${pan.last4} exp ${pan.expiry} ${pan.cardholderName}"
        }
        is ScanEvent.VouchersMatched -> { /* event.matches */ }
        is ScanEvent.Error -> { /* event.error */ }
    }
}
engine.start(preview)
```

No manual `shutdown()` call is needed — the engine observes your
`LifecycleOwner` and shuts itself down on `ON_DESTROY`.

## 3. Without a camera (text you already have)

```kotlin
LuhnValidator.extractCard(ocrText)?.use { pan ->
    println(pan)  // redacted: SecurePan(VISA, ****1234)
}

// Also wants expiry/cardholder name from the same OCR pass:
LuhnValidator.extractCardDetails(ocrText)?.use { pan ->
    println("${pan.expiry} ${pan.cardholderName}")  // e.g. ExpiryDate(12, 2029) JOHN DOE
}
```

`LuhnValidator`/`SpatialMatcher` live in `aegisscan-core`, a plain
Kotlin/JVM module — no Android dependency, usable in a backend service too.

## 4. ID document scanning (MRZ)

A separate capability from card scanning — parses a passport or ID card's
machine-readable zone, not something `AndroidCameraScannerEngine` detects
automatically:

```kotlin
MrzParser.extractMrz(ocrText)?.let { mrz ->
    println("${mrz.documentNumber} ${mrz.surname} ${mrz.givenNames}")
    if (mrz.compositeCheckValid) println("MRZ checksum passed")
}
```

Unlike expiry/cardholder name, every MRZ field is backed by an ICAO 9303
check digit — see `docs/technical/01-core-engine.md`'s MRZ section. It's
PII, not PCI-DSS cardholder data, so it's a different compliance
consideration than the card fields above. `MrzParser` also lives in the
plain `aegisscan-core` module.

## Tuning

`ScannerConfig(detectCards, detectVouchers, requiredConsecutiveCardHits,
targetWidth, targetHeight, minAspectRatio, maxAspectRatio)`.

## Must-not-do

- Don't hold onto `event.pan` outside the lambda that received it.
- Don't skip the `AndroidManifest.xml` `<uses-permission
  android:name="android.permission.CAMERA" />` — `engine.start()` checks it
  and reports `ScannerError.CameraPermissionDenied` instead of crashing, but
  scanning obviously won't work without it.
