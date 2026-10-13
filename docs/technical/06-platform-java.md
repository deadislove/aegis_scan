# Platform: Java

Source: `bindings/java/src/main/java/com/aegisscan/interop/`
(`FintechCoreInterop.java` — Option A; `jni/FintechCoreJni.java` +
`core/bindings/jni/aegis_jni.cpp` — Option B). Sample: `sample/java/` (runs
both options against the real native libraries). Like .NET, this is a
**logic library**, not a camera pipeline.

## Option A: `FintechCoreInterop` (JDK 21+ FFM / Project Panama)

Preferred for new backend or desktop code on JDK 21+.

- Every call allocates its temporary off-heap memory in a **confined
  `Arena`** (`try (Arena arena = Arena.ofConfined())`), freed deterministically
  when the call returns — never by the GC. The library itself is loaded
  into a **shared `Arena`** owned by the `FintechCoreInterop` instance,
  released in `close()`.
- `MethodHandle`s are bound once at construction (`Linker.nativeLinker()
  .downcallHandle(...)`) against explicit `FunctionDescriptor`s (e.g.
  `aegis_matcher_match`'s eight-argument descriptor) — not re-resolved per
  call.
- Struct layouts (`ITEM`, `MATCH`, `METRICS`) are defined with
  `MemoryLayout.structLayout(...)`, matching `aegis_item`/`aegis_match`/
  `aegis_frame_metrics` field-for-field, including the C struct's tail
  padding (`paddingLayout(4)` on `ITEM`) — getting this wrong silently
  misreads every field after the first mismatch, so it's worth
  double-checking against `fintech_core_c.h` if you ever add a field.
- Native PAN buffers are zeroed with `aegis_secure_zero` *before* their
  arena closes, in addition to the `SecurePan` wipe on the Java side.
- `extractExpiry`/`extractCardholderName` have no UTF-16 native entry point
  (unlike `extractPan`), so the `CharSequence` is encoded to UTF-8
  (`String.getBytes(StandardCharsets.UTF_8)`) before crossing the boundary.
  `extractCardDetails` runs all three extractions over one OCR pass and
  attaches the results to one `SecurePan`.
- `JDK 21`: FFM is still a **preview feature**, so compiling and running
  code that uses it needs `--enable-preview` (and
  `--enable-native-access=ALL-UNNAMED` to silence/allow the native-access
  warning). The same source also works unmodified on **JDK 22+**, where the
  API is final — see `sample/java/Makefile`.

## Option B: `FintechCoreJni` (legacy JNI fallback)

This is the same native path **Android** uses
(`core/bindings/jni/aegis_jni.cpp`), so it's relevant even on newer JDKs if
you specifically need JNI (e.g. embedding in an existing JNI-based system).

- Text goes in as `char[]`, pinned with `GetPrimitiveArrayCritical` on the
  native side (no copy) and always released through an RAII guard —
  critical sections are kept as short as possible since they can block GC.
- `extractExpiry`/`extractCardholderName` convert the pinned UTF-16 buffer
  to UTF-8 on the native side (`Utf16ToUtf8` in `aegis_jni.cpp`, surrogate
  pairs included) before calling the UTF-8-only core functions — there's no
  UTF-16 native entry point for these, unlike `extractPan`.
- Images go in as **direct** `ByteBuffer`s (`GetDirectBufferAddress`, zero
  copy); `FintechCoreJni.analyzeLuma` throws `IllegalArgumentException` if
  you pass a heap buffer, by design, rather than silently copying.
- `FintechCoreJni.load(absolutePath)` / `loadLibrary()` (java.library.path)
  — call exactly one of these once; it's idempotent (`loaded` is a
  `volatile boolean` guarded by `synchronized`).
- The instance owns a native matcher handle (a `long`); use
  try-with-resources so `close()` frees it deterministically.

## Shared types

`SecurePan` (wipeable `char[]`, `AutoCloseable`, redacted `toString()`),
`CardBrand` (enum mirroring `AEGIS_BRAND_*`), `ExpiryDate` (a record:
`month`, `year`), `FrameMetrics` (a record: `status`, `meanLuma`,
`focusScore`, `ok()`), `FintechCoreException` (carries only a status code —
never input data or addresses).

## See also

- [Core Engine](01-core-engine.md).
- [Security & PCI-DSS](07-security-pci-dss.md).
- `sample/java/README.md` — `make run` builds the native libs if missing
  and runs both options back to back.
