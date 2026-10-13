# Quickstart: .NET

Full technical detail: `docs/technical/05-platform-dotnet.md`. Runnable
sample: `sample/csharp/` (`dotnet run`, see its `README.md`). This is a
logic library — you supply frames/OCR text from whatever camera or capture
API your app already uses (MAUI `CameraView`, a WPF `CaptureElement`, a USB
scanner feed, ...).

## 1. Build the native core once

```bash
cd core && make lib
```

Produces `core/build/libaegis_core.{dylib,so}` (or a cross-compiled
`aegis_core.dll` for Windows) — see
`docs/technical/08-build-and-linking.md` for how to ship this inside a
NuGet package or MAUI app.

## 2. Reference the project

```xml
<ItemGroup>
  <ProjectReference Include="path/to/bindings/dotnet/AegisScan.Interop/AegisScan.Interop.csproj" />
</ItemGroup>
```

## 3. Validate and extract

```csharp
using AegisScan.Interop;

bool ok = FintechCoreWrapper.ValidateLuhn("4111111111111111");

using SecurePan? pan = FintechCoreWrapper.ExtractPan(ocrText);
if (pan is not null)
{
    Console.WriteLine(pan);          // redacted: SecurePan(Visa, ****1234)
    pan.UseDigits(digits =>
    {
        // Send `digits` to your tokenizer/HSM call here, inside this
        // scope -- `pan` is disposed (wiped) right after `using` exits.
        return 0;
    });
}

// Also wants expiry/cardholder name from the same OCR pass (best-effort --
// no checksum backs them, unlike the PAN):
using SecurePan? details = FintechCoreWrapper.ExtractCardDetails(ocrText);
Console.WriteLine($"{details?.Expiry} {details?.CardholderName}");  // e.g. ExpiryDate { Month = 12, Year = 2029 } JOHN DOE
```

## 4. ID document scanning (MRZ)

A separate capability from card scanning — parses a passport or ID card's
machine-readable zone:

```csharp
MrzResult? mrz = FintechCoreWrapper.ExtractMrz(ocrText);
if (mrz is not null)
{
    Console.WriteLine($"{mrz.DocumentNumber} {mrz.Surname} {mrz.GivenNames}");
    if (mrz.CompositeCheckValid) Console.WriteLine("MRZ checksum passed");
}
```

Unlike expiry/cardholder name, every MRZ field is backed by an ICAO 9303
check digit — see `docs/technical/01-core-engine.md`'s MRZ section. It's
PII, not PCI-DSS cardholder data, so it's a different compliance
consideration than the card fields above.

## 5. Voucher matching

```csharp
using var wrapper = new FintechCoreWrapper(frameWidth: 1080, frameHeight: 1920);
var matches = wrapper.MatchBarcodesWithText(barcodes, texts);
```

Create one `FintechCoreWrapper` per logical session (e.g. per POS terminal
process), not per frame — it owns a native matcher handle freed on
`Dispose()`.

## Must-not-do

- Don't call `.UseDigits(...)` outside the `using` scope that owns the
  `SecurePan` — it throws `ObjectDisposedException` once wiped, by design.
- Don't create a new `FintechCoreWrapper` per frame; reuse one and
  `Dispose()` it when the session ends.
