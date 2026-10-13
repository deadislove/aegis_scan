# Platform: .NET

Source: `bindings/dotnet/AegisScan.Interop/` (`FintechCoreWrapper.cs`,
`FintechCoreWrapper.Matcher.cs`, `SecurePan.cs`, `NativeMethods.cs`).
Sample: `sample/csharp/` (console app). This is a **logic library** —
it calls the C ABI directly; it does not capture camera frames itself (see
[Architecture](00-architecture.md)'s "n/a" row for .NET/Java). A MAUI app or
Windows POS terminal supplies frames/OCR text from whatever camera API it
already uses and passes them to `FintechCoreWrapper`.

## Why `[LibraryImport]`, not `[DllImport]`

Every native entry point in `NativeMethods.cs` uses .NET 8's
source-generated `[LibraryImport]` instead of the legacy `[DllImport]`.
The marshalling stubs are generated **at compile time**, so there's no
runtime IL-stub generation — and, critically, it's **Native AOT and
trimming compatible** (`<IsAotCompatible>true</IsAotCompatible>` in the
`.csproj`), which `[DllImport]`'s runtime marshaller is not. Every P/Invoke
signature in this file is restricted to blittable types for exactly this
reason.

## Zero-copy call shapes

- `ExtractPan(ReadOnlySpan<char> rawOcr)`: the span is pinned (`fixed`) and
  handed straight to `aegis_extract_pan_utf16`; the UTF-16 output buffer is
  `stackalloc`'d and wiped (`CryptographicOperations.ZeroMemory`) in a
  `finally` block regardless of outcome.
- `AnalyzeLuma(ReadOnlySpan<byte> luma, ...)`: pinned, passed directly —
  overloads exist for `ReadOnlyMemory<byte>` (pooled/native camera buffers)
  and `byte[]` (resolves span/memory overload ambiguity, still zero-copy).
- `MatchBarcodesWithText`: all barcode/text strings for one call are
  UTF-8-encoded into **one pooled arena** (`ArrayPool<byte>.Shared.Rent`),
  pinned once, and handed to `aegis_matcher_match` as an array of
  `AegisItem` structs pointing into that arena — one pin/unpin per call, not
  one per string.
- `ExtractExpiry`/`ExtractCardholderName`: unlike `ExtractPan`, there's no
  UTF-16 native entry point for these, so the span is transcoded to UTF-8
  (`Encoding.UTF8.GetBytes`, `stackalloc` below 512 bytes) before crossing
  the boundary. `ExtractCardDetails` runs all three extractions over the
  same OCR text in one call and attaches the results to one `SecurePan`.

## `SpatialMatcherHandle : SafeHandle`

`FintechCoreWrapper` owns one `aegis_matcher_t*` through a `SafeHandle`
(`SpatialMatcherHandle`), which gives critical finalization and protection
against handle-recycling races during concurrent P/Invoke calls — on top of
the deterministic `Dispose()` → `aegis_matcher_destroy` path. Matching
itself is serialized per instance (`lock(_matchLock)`); stateless calls
(`ValidateLuhn`, `ExtractPan`, `AnalyzeLuma`) are thread-safe without a lock.

## `SecurePan`, not `SecureString`

Microsoft marks `SecureString` obsolete for new code — it provides no
protection beyond what a deterministic wipe already gives you, at the cost
of significant complexity. `SecurePan` instead:

- allocates its digit buffer on the **Pinned Object Heap**
  (`GC.AllocateArray<char>(..., pinned: true)`), so the GC can never
  relocate it and leave an unwiped copy behind;
- wipes with `CryptographicOperations.ZeroMemory`, which the JIT cannot
  optimize away (unlike a hand-written loop);
- has a redacted `ToString()` and no serialization attributes, so it can't
  end up in a log or persisted JSON by accident.

## See also

- [Core Engine](01-core-engine.md) for what's behind these calls.
- [Security & PCI-DSS](07-security-pci-dss.md).
- `sample/csharp/README.md` — build the native lib once, then `dotnet run`.
