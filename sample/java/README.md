# AegisScan Java Sample

Drives both Java interop options in `bindings/java` against the real native
core:

- **Option A -- `FintechCoreInterop`**: JDK 21+ Foreign Function & Memory API
  (Project Panama). Preferred for new backend/desktop code.
- **Option B -- `FintechCoreJni`**: legacy JNI fallback. This is the same
  path Android uses (`core/bindings/jni/aegis_jni.cpp`).

`FintechCoreInterop`/`FintechCoreJni` are logic libraries (no camera) — for
a backend validation service, call them the same way this sample does,
from whatever receives the OCR text or frame buffer (a queue consumer, an
HTTP handler, …). This sample also includes an optional **live camera
mode** (`--camera`, see below) that opens your Mac's webcam directly, for
testing the real pipeline end to end without a separate host app.

## Prerequisites

- JDK 21+ (FFM is a preview feature on 21, final on 22+; this sample's
  Makefile passes `--enable-preview` so it works on either)
- `make` and a C++20 compiler (to build the native libs on first run)

No Maven/Gradle project is set up here, matching how `bindings/java` itself
builds (see the root `README.md`).

## Run

```bash
make run
```

This builds `core/build/libaegis_core.{dylib,so}` and `libaegis_jni.{dylib,so}`
if they don't exist yet (`make -C ../../core lib jni`), compiles the sample
together with `bindings/java`, and runs it with both interop options.

Expected output (abridged):

```
=== Option A: FintechCoreInterop (FFM / Project Panama) ===
native version: 1.0.0
validateLuhn(4111111111111111) = true
extractPan found: SecurePan(VISA, ****1111) brand=VISA
...
=== Option B: FintechCoreJni (legacy JNI fallback) ===
validateLuhn(4111111111111111) = true
extractPan found: Pan(brand=1, ****1111)
...
```

## Raw commands (if you'd rather not use the Makefile)

```bash
cd ../../core && make lib jni && cd -
javac --release 21 --enable-preview -d out \
  $(find ../../bindings/java/src/main/java -name '*.java') \
  $(find src/main/java -name '*.java')
java --enable-preview --enable-native-access=ALL-UNNAMED -cp out com.aegisscan.sample.Main
```

## Live camera mode (`--camera`)

```bash
make run ARGS=--camera
```

Opens this Mac's webcam directly and loops: capture a frame, run OCR, run
barcode decoding, feed the result through `FintechCoreInterop.extractPan`.
Press Ctrl+C to stop.

Like the .NET sample, this doesn't reimplement OCR/barcode decoding in
Java — it shells out to the same three CLI tools:

```bash
brew install imagesnap   # grabs a frame from the webcam
brew install tesseract   # OCR (same engine the web sample uses, via WASM there)
brew install zbar        # provides zbarimg, for barcode decoding
```

Every frame's OCR/barcode output is run through this SDK's own real
`extractPan` (repair + Luhn + brand) logic, via the FFM path — that's the
part this sample actually demonstrates.

## What to copy into your app

- Always load `FintechCoreInterop`/`FintechCoreJni` with try-with-resources
  (`close()` frees the native matcher and, for FFM, unloads the library).
- `extractPan` returns a `SecurePan`/`Pan` — also close it as soon as you've
  handed the digits to your tokenizer. Never call `.toString()` in a log
  statement; it's redacted on purpose but shouldn't be relied on as your only
  safeguard.
- `analyzeLuma` requires a **direct** `ByteBuffer` — a heap buffer throws
  `IllegalArgumentException`, by design, since the zero-copy guarantee
  wouldn't hold otherwise.
