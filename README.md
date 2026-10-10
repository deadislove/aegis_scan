# AegisScan SDK

Zero-disk, low-latency payment-card and voucher scanner for iOS, Android, Web,
.NET 8, and Java 21+.

| Layer | Path | Tech |
|---|---|---|
| Core engine | `core/include/aegisscan/fintech_core.hpp` | C++20 header-only, inline GoogleTest |
| Stable C ABI | `core/include/aegisscan/fintech_core_c.h`, `core/src/fintech_core_c.cpp` | used by Swift, C#, Java FFM, JNI |
| iOS | `Package.swift`, `ios/Sources/AegisScan/` | AVFoundation + Vision, `CameraScannerPipeline` |
| Android | `android/` | CameraX + ML Kit, `AndroidCameraScannerEngine`; pure-Kotlin core port |
| Web | `web/src/FintechWebScanner.ts`, `web/src/scanner.worker.ts` | getUserMedia + Worker + OffscreenCanvas |
| .NET 8 | `bindings/dotnet/AegisScan.Interop/` | `[LibraryImport]`, SafeHandle, Span, wipeable PAN |
| Java 21+ | `bindings/java/` | FFM/Panama + Arena (Option A), JNI with Critical arrays + direct ByteBuffer (Option B) |

## Guarantees shared by every platform

* **Zero disk.** No file, cache, `UserDefaults`, `SharedPreferences`,
  `localStorage`, `sessionStorage`, or IndexedDB access anywhere. No logging of
  OCR text or PANs.
* **Wipeable PANs.** PANs travel as `CardNumber` (C++), `SecurePAN` (Swift),
  `SecurePan` (Kotlin/Java/C#), `Uint8Array` (JS), or `char[]` (JNI). They are
  zeroed right after the result callback returns, and `toString`/`description`
  is redacted to `****1234`.
* **Atomic frame dropping.** A single in-flight frame. Frames that arrive
  while busy are released immediately (iOS `AtomicFlag` + `alwaysDiscardsLateVideoFrames`,
  Android `AtomicBoolean` + `STRATEGY_KEEP_ONLY_LATEST`, Web `isProcessing`
  checked before `createImageBitmap`).
* **Defensive input.** Null or corrupt buffers, bad strides, truncated planes,
  extreme aspect ratios, dark frames, NaN boxes, and denied permissions all
  produce typed errors, never crashes.
* **Shared test vectors.** The same OCR edge cases (8↔B, 0↔O/D/Q, 1↔l/I/|,
  5↔S/$, 2↔Z, 4↔A, noisy text, CVV suffixes, glued digits) run in every language.

## Samples and docs

* `sample/` — a runnable example per platform (iOS, Android, Web, .NET, Java),
  plus a macOS camera harness for testing the core logic against a real
  webcam without a phone. Each has its own README.
* `docs/technical/` — architecture, the core engine's algorithms, and
  per-platform implementation notes.
* `docs/guide/` — getting started, a quickstart per platform, and the
  business case for on-device scanning.

## Build & test

```bash
# C++ core (GoogleTest checkout at /tmp/aegis_gtest or GTEST_DIR=...)
cd core
make test      # 26 tests
make asan      # AddressSanitizer + UBSan
make tsan      # ThreadSanitizer (concurrency suites)
make lib jni   # libaegis_core.{dylib,so} + libaegis_jni.{dylib,so}

# Swift (host-side tests on macOS; ship to iOS 15+ via SwiftPM)
swift test && swift test --sanitize=thread

# Android (needs Android SDK)
cd android && ./gradlew :aegisscan-core:test :aegisscan-camera:testDebugUnitTest

# Web
cd web && npm install && npm test

# Java (JDK 21: FFM is preview there, final in 22+)
javac --release 21 --enable-preview -cp junit-console.jar -d out $(find bindings/java/src -name '*.java')
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -Daegis.core.lib=core/build/libaegis_core.dylib -Daegis.jni.lib=core/build/libaegis_jni.dylib \
     -jar junit-console.jar execute -cp out --select-class com.aegisscan.interop.InteropTest

# .NET
cd bindings/dotnet && dotnet test AegisScan.Interop.Tests
```

## Linking the native core

The native library exports only the `aegis_*` C symbols (built with
`-fvisibility=hidden`). Build it once per target platform:

| Platform | Build | Artifact |
|---|---|---|
| macOS | `make -C core lib` | `libaegis_core.dylib` (install name `@rpath/libaegis_core.dylib`) |
| Linux | `make -C core lib` | `libaegis_core.so` |
| Windows (MSVC) | `cl /std:c++20 /O2 /EHsc /LD /DAEGIS_CORE_BUILD /Icore\include core\src\fintech_core_c.cpp /Fe:aegis_core.dll` | `aegis_core.dll` |
| Android | NDK: `$NDK/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android24-clang++ -std=c++20 -O2 -fPIC -shared -fvisibility=hidden -DAEGIS_CORE_BUILD -Icore/include core/src/fintech_core_c.cpp core/bindings/jni/aegis_jni.cpp -o libaegis_jni.so` (repeat per ABI) | `jniLibs/<abi>/libaegis_jni.so` |
| iOS | Built automatically by SwiftPM (`CAegisCore` target) | static, linked into the app |

**.NET 8.** `[LibraryImport("aegis_core")]` resolves `aegis_core.dll`,
`libaegis_core.so`, or `libaegis_core.dylib` from the application directory.
Ship it as `runtimes/<rid>/native/<file>` in a NuGet package
(`win-x64`, `linux-x64`, `osx-arm64`, …) and the runtime picks the right one.
For MAUI, add it as a `NativeReference` (iOS) or `AndroidNativeLibrary`
(Android, using the `.so` names). If you need a custom location, use
`NativeLibrary.SetDllImportResolver`.

**Java FFM.** Pass the absolute path to `new FintechCoreInterop(Path)`, or put
the library on `-Djava.library.path`. Run with
`--enable-native-access=ALL-UNNAMED` (plus `--enable-preview` on JDK 21).

**Java/Android JNI.** Call `FintechCoreJni.load("/abs/path/libaegis_jni.so")`,
or `loadLibrary()` with the library on `java.library.path` or in `jniLibs/`.
`libaegis_jni` statically contains the core, so only one file ships.

## Notes and limitations

* Web OCR is pluggable (`ocrModuleUrl` → module exporting `recognize(ImageData)`).
  Browsers have no built-in text recognizer, so card detection on the web needs
  such a module; `sample/web/ocr-tesseract.js` is a working example backed by
  Tesseract.js. Barcodes use the native `BarcodeDetector`, with
  `barcodeFallbackUrl` as the WASM/JS fallback.
* The Android CameraX/ML Kit engine was written against CameraX 1.4 and ML Kit
  bundled models. Its pure-Kotlin core and `FrameGuard` are unit-tested on the
  JVM. The camera module itself needs the Android SDK to build.
* `SecureString` is intentionally not used in .NET (Microsoft discourages it
  for new code). A pinned, zeroed `char[]` provides the actual guarantee.
* 30 FPS / < 15% CPU depends on the device. With atomic dropping, inference
  cost lowers the analyzed-frame rate, not the preview rate.
* OCR accuracy on live camera input varies with print quality, lighting, and
  layout complexity (e.g. a barcode printed close to the card number can
  confuse a general-purpose OCR engine). The platform-native recognizers
  (Vision, ML Kit) handle this better than the plain-Tesseract paths used in
  the Web and CLI samples — see `docs/technical/` for the accuracy/engine
  tradeoffs per platform.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Security

Found a vulnerability? See [SECURITY.md](SECURITY.md) for how to report it
privately. Please don't open a public issue for security problems.
