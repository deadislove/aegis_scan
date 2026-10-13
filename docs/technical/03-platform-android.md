# Platform: Android

Source: `android/aegisscan-core` (pure-Kotlin/JVM core port, no Android
dependency) and `android/aegisscan-camera` (CameraX + ML Kit engine).
Sample: `sample/android/` (wired into `android/settings.gradle.kts` via a
custom `projectDir` — see that file and the sample's README).

## `aegisscan-core`: a bit-for-bit Kotlin port

Android does **not** link the C++ core through JNI for its logic layer —
`LuhnValidator.kt` and `SpatialMatcher.kt` are a from-scratch Kotlin port,
verified against the same shared test vectors as the C++ core
(`CoreTests.kt`). This keeps a plain Kotlin/JVM module (no NDK, no
`System.loadLibrary`) usable standalone — e.g. for server-side validation in
a Kotlin backend — while the Android *camera* module above it still gets
the full native-grade algorithm behavior. The JNI path is also available
separately if you prefer it (`bindings/java`'s `FintechCoreJni`, which
Android can call too — see [Platform: Java](06-platform-java.md)).

`SecurePan` here is a wipeable `CharArray` (never `String`), `AutoCloseable`
so `use { }` wipes it deterministically; `toString()` is redacted.

`LuhnValidator.extractExpiry`/`extractCardholderName` are best-effort,
unwiped `ExpiryDate?`/`String?` scanned from the same OCR text — no
checksum backs them the way Luhn backs the PAN, and neither is PCI-DSS
cardholder data on its own. `LuhnValidator.extractCardDetails` runs all
three extractions over one OCR pass and attaches the results to one
`SecurePan`; `AndroidCameraScannerEngine.processCard` calls it instead of
`extractCard` so `ScanEvent.CardDetected.pan.expiry`/`.cardholderName` are
populated automatically.

## `AndroidCameraScannerEngine`

Implements `ImageAnalysis.Analyzer`. Two-layer back-pressure:

1. **CameraX**: `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` — CameraX itself
   holds at most one pending frame and drops older ones.
2. **SDK gate**: `isProcessingFrame` (`AtomicBoolean`). `analyze()` tries to
   take it with `compareAndSet(false, true)`; on failure the new frame is
   closed and dropped immediately — zero work done.

`imageProxy.close()` is **always** called in a `finally` block: immediately
on drop/reject/error, or — since ML Kit reads the underlying `media.Image`
asynchronously — only after both ML Kit tasks (`TextRecognition`,
`BarcodeScanning`, run concurrently via `kotlinx.coroutines.async`) have
finished. Closing early would hand ML Kit a buffer CameraX might already be
recycling.

**Frame validation** (`FrameGuard.validate`, pure Kotlin, independently unit
-testable on the JVM without Android) mirrors the C++ core's
`FrameAnalyzer`: checks format (`YUV_420_888`), dimensions, stride, buffer
size, aspect ratio, before any ML Kit call.

**Lifecycle**: the engine registers a `DefaultLifecycleObserver` on
construction and calls its own `shutdown()` on `ON_DESTROY` — closing both
ML Kit clients and shutting down the dedicated analysis
`ExecutorService`/`CoroutineDispatcher` automatically, so a caller that
forgets to call `shutdown()` explicitly still doesn't leak.

**Card confirmation**: same pattern as iOS — `ScannerConfig
.requiredConsecutiveCardHits` (default 2) consecutive analyzed frames must
extract the same PAN (`SecurePan.contentEquals`) before `ScanEvent
.CardDetected` fires on the main thread.

## `ScanEvent` / `ScannerError` / `ScannerConfig`

`ScanEvent` is a `sealed interface`: `CardDetected(pan: SecurePan)`,
`VouchersMatched(matches: List<MatchResult>)`, `Error(error: ScannerError)`.
`CardDetected.toString()` is redacted. `ScannerConfig` carries
`detectCards`/`detectVouchers`/`requiredConsecutiveCardHits`/
`targetWidth`/`targetHeight`/`minAspectRatio`/`maxAspectRatio`/
`reportFrameRejections`.

## See also

- [Core Engine](01-core-engine.md) for the algorithm this port mirrors.
- [Security & PCI-DSS](07-security-pci-dss.md).
- `sample/android/README.md` — build/install commands and what the sample's
  `MainActivity.kt` wires up.
