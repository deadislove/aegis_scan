# Quickstart: Java

Full technical detail: `docs/technical/06-platform-java.md`. Runnable
sample: `sample/java/` (`make run`, see its `README.md`). Like .NET, this is
a logic library — you supply frames/OCR text yourself.

## 1. Build the native libraries once

```bash
cd core && make lib jni
```

## 2. Option A -- FFM (JDK 21+, preferred for new code)

```java
import com.aegisscan.interop.FintechCoreInterop;
import com.aegisscan.interop.SecurePan;
import java.nio.file.Path;

try (FintechCoreInterop core = new FintechCoreInterop(Path.of("core/build/libaegis_core.dylib"))) {
    boolean ok = core.validateLuhn("4111111111111111");

    try (SecurePan pan = core.extractPan(ocrText.toCharArray())) {
        if (pan != null) {
            System.out.println(pan);  // redacted: SecurePan(VISA, ****1234)
            // Send digits to your tokenizer/HSM call here, inside this
            // try block -- `pan` is wiped as soon as it closes.
        }
    }

    // Also wants expiry/cardholder name from the same OCR pass (best-effort
    // -- no checksum backs them, unlike the PAN):
    try (SecurePan details = core.extractCardDetails(ocrText.toCharArray())) {
        if (details != null) System.out.println(details.expiry() + " " + details.cardholderName());
    }
}
```

Compile and run with `--enable-preview` on JDK 21 (final on JDK 22+); see
`sample/java/Makefile` for the exact flags, including
`--enable-native-access=ALL-UNNAMED`.

## 3. Option B -- JNI (legacy, or if you're already JNI-based / on Android)

```java
import com.aegisscan.interop.jni.FintechCoreJni;

FintechCoreJni.load("/abs/path/libaegis_jni.dylib");
try (FintechCoreJni jni = new FintechCoreJni(0, 0)) {
    boolean ok = jni.validateLuhn("4111111111111111".toCharArray());
    try (FintechCoreJni.Pan pan = jni.extractPan(ocrText.toCharArray())) {
        if (pan != null) System.out.println(pan);
    }
}
```

## Must-not-do

- Don't pass a heap `ByteBuffer` to `analyzeLuma` — both options require a
  **direct** buffer and throw `IllegalArgumentException` otherwise, rather
  than silently copying.
- Don't call `FintechCoreJni.load`/`loadLibrary` more than once per
  process with different paths — it's a one-time, idempotent load.
