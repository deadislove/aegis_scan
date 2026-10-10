# Architecture Overview

AegisScan is a single C++20 core (`aegisscan::core`) wrapped by five thin,
idiomatic platform layers. The core owns every algorithm; the platform
layers own camera capture, threading, and memory ownership for their runtime.

```
                    ┌─────────────────────────────────────────┐
                    │   core/include/aegisscan/fintech_core.hpp │
                    │   (header-only C++20, zero dependencies)  │
                    │                                           │
                    │   LuhnValidator   SpatialMatcher          │
                    │   CardNumber      FrameAnalyzer           │
                    └───────────────────┬───────────────────────┘
                                         │ stable C ABI
                    ┌────────────────────┴────────────────────┐
                    │ core/include/aegisscan/fintech_core_c.h   │
                    │ core/src/fintech_core_c.cpp                │
                    └───┬───────┬───────┬───────┬───────┬───────┘
                        │       │       │       │       │
                  Swift/C++  Kotlin  TS/WASM  C#/.NET  Java
                   (iOS)   (Android) (Web)    (LibraryImport) (FFM + JNI)
```

## Why a shared core

Luhn validation, OCR character repair, BIN/IIN brand detection, and
barcode↔text spatial matching are pure, platform-independent algorithms.
Implementing them five times would mean five places to find the same bug.
Instead:

- **C++20 core** (`core/include/aegisscan/fintech_core.hpp`): implements
  everything once, header-only, no external dependencies, with inline
  GoogleTest unit tests. See [Core Engine](01-core-engine.md).
- **C ABI** (`fintech_core_c.h` / `fintech_core_c.cpp`): the only thing every
  other language links against. A C ABI is the one interface every FFI
  system (Swift's C interop, JNI, .NET P/Invoke, Kotlin/JNI) can consume
  without a code generator.
- **Android and Web also carry a native-logic port** (`android/aegisscan-core`
  in Kotlin, `web/src/core/*.ts` in TypeScript) instead of linking the C ABI
  directly. Android could link the C++ core via JNI exactly like the Java
  bindings do, but a pure-Kotlin port avoids forcing a NDK dependency on
  every Android consumer; the Web platform has no C ABI story at all without
  WASM, so its core is a direct TypeScript port. Both ports are verified
  against **the same test vectors** as the C++ core (8↔B, 0↔O/D/Q, 1↔l/I/|,
  5↔S/$, 2↔Z, 4↔A, plus noisy/grouped text and CVV suffixes), so behavior
  stays identical across all five platforms even where the binary isn't
  shared.

## Per-platform capture pipelines

Camera capture, frame back-pressure, and threading are necessarily
platform-specific — each OS has its own camera API and concurrency
primitives. All five follow the same shape:

| Platform | Capture API | Vision/ML API | Core logic path |
|---|---|---|---|
| iOS | `AVCaptureSession` (`AVCaptureVideoDataOutput`) | Apple `Vision` (`VNRecognizeTextRequest`, `VNDetectBarcodesRequest`) | Swift calls the C ABI directly |
| Android | CameraX (`ImageAnalysis`) | Google ML Kit (`TextRecognition`, `BarcodeScanning`) | Kotlin port (`aegisscan-core`) |
| Web | `getUserMedia` + `requestAnimationFrame` | native `BarcodeDetector` + pluggable OCR module | TypeScript port (`web/src/core`), run inside a Web Worker |
| .NET | n/a (SDK consumer supplies frames) | n/a | C# calls the C ABI via source-generated `[LibraryImport]` |
| Java | n/a (SDK consumer supplies frames) | n/a | Java calls the C ABI via FFM (Panama) or JNI |

"n/a" for .NET/Java means those bindings are a **logic library**, not a
camera pipeline — see [Platform: .NET](05-platform-dotnet.md) and
[Platform: Java](06-platform-java.md) for how a POS app or backend service
is expected to supply frames/OCR text itself.

## Back-pressure: one shared pattern, five implementations

Every capture pipeline uses the identical strategy: **at most one frame in
flight; a frame that arrives while busy is dropped immediately, at O(1)
cost, never queued.**

| Platform | Gate primitive | Extra OS-level drop |
|---|---|---|
| iOS | `AtomicFlag` (`isProcessingFrame`) | `videoOutput.alwaysDiscardsLateVideoFrames = true` |
| Android | `AtomicBoolean` (`isProcessingFrame`) | `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` |
| Web | plain `boolean` (`isProcessing`, main thread is single-threaded so check-and-set is atomic) | frame isn't even captured (`createImageBitmap`) while busy |

This is what keeps latency bounded regardless of how expensive inference
gets on a given device: worst case, the user sees the last successfully
analyzed frame's result a little later, never a growing backlog.

## PCI-DSS zero-disk guarantee

No platform ever writes a pixel buffer, recognized text, or PAN to disk,
`UserDefaults`/`SharedPreferences`, `localStorage`/`IndexedDB`, or a log
statement. See [Security & PCI-DSS](07-security-pci-dss.md) for the
wipeable-buffer design (`SecurePAN`/`SecurePan`/`CardNumber`) that backs
this on every platform.

## Where to go next

- [Core Engine](01-core-engine.md) — the algorithms themselves
- Per-platform technical notes: [iOS](02-platform-ios.md),
  [Android](03-platform-android.md), [Web](04-platform-web.md),
  [.NET](05-platform-dotnet.md), [Java](06-platform-java.md)
- [Security & PCI-DSS](07-security-pci-dss.md)
- [Build & Linking](08-build-and-linking.md)
- For integration how-tos and the business case, see `docs/guide/`.
