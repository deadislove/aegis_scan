# FAQ

**Does AegisScan make my app PCI-DSS compliant?**
No single SDK does that for you. AegisScan is designed so that the
scanning step itself doesn't expand your compliance scope (no disk, no
logs, no third-party upload of card images) — but what your app does with
the validated number afterward (storage, transmission, processing) is
still your responsibility and still needs its own compliance review. See
`docs/technical/07-security-pci-dss.md` for exactly what's covered.

**Does it work offline?**
Yes, on every platform — scanning is entirely on-device. There's no
network call anywhere in the card-detection or barcode-matching path.

**Which card brands does it detect?**
Visa, Mastercard, Amex, and JCB, via IIN/BIN range plus length plus Luhn —
see `docs/technical/01-core-engine.md` for the exact ranges. A number that
passes Luhn but doesn't match a known brand range is rejected, by design
(it's usually not a card number).

**How accurate is the OCR repair?**
It fixes a specific, fixed set of common misreads (`O/D/Q→0`, `I/l/|→1`,
`Z→2`, `S/$→5`, `B→8`, `A→4`) and requires at least half of a candidate's
characters to already be genuine digits before it will "rescue" the rest —
it will not attempt to repair a mostly-garbled string, which keeps the
false-positive rate low at the cost of occasionally missing a very noisy
read. Combined with requiring 2 consecutive matching frames before
reporting a card (tunable), false positives in practice are rare.

**Can I lower/raise the "2 consecutive frames" requirement?**
Yes — `requiredConsecutiveCardHits` on every camera-facing platform
(`ScannerConfiguration`/`ScannerConfig`/`FintechWebScannerOptions`). Lower
it for faster detection at the cost of more OCR-noise false positives;
raise it for the opposite tradeoff.

**Does the Web SDK do OCR without any setup?**
Barcode detection, yes (native `BarcodeDetector` where supported). Card OCR
needs a module you provide (`ocrModuleUrl`) — browsers have no built-in
text recognizer. See `docs/guide/03-quickstart-web.md`.

**Why does Android have its own Kotlin port of the core instead of using
JNI like the Java bindings do?**
So a pure Kotlin/JVM consumer (e.g. a backend service, or the
`aegisscan-core` module itself) never needs an NDK dependency. The Android
*camera* module still gets full native-grade behavior because the Kotlin
port is verified against the same test vectors as the C++ core. If you
specifically want the JNI path on Android, `bindings/java`'s
`FintechCoreJni` works there too.

**Can I use this without a camera — just pass it text or a frame I already
have?**
Yes, every platform's core logic (Luhn validation, OCR repair/extraction,
spatial matching, frame-quality analysis) is callable directly; the camera
pipeline classes (`CameraScannerPipeline`, `AndroidCameraScannerEngine`,
`FintechWebScanner`) are a convenience layer on top. See each quickstart's
"Without a camera" section.

**Where do I report what my app should do with a scanned card number?**
Nowhere in this SDK — by design. Hand it to your tokenizer, payment
gateway, or HSM synchronously inside the callback that received it, then
let the SDK wipe its buffer. See the "Must-not-do" section of each
quickstart.

**What hasn't been measured on real hardware yet?**
The real-camera FPS/CPU targets (30 FPS, <15% CPU) are design targets, not
numbers measured across the full device matrix — verify them on your
actual target hardware before shipping, especially on older/low-end
devices. Same caveat for the Android camera module: its unit tests pass on
the JVM, but run it on a physical device as part of your own QA pass
before relying on it.
