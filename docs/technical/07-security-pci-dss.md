# Security & PCI-DSS

AegisScan's core design constraint: **never save pixel buffers, card
images, or extracted raw PANs to local storage.** Everything below is how
that constraint is actually enforced on each platform, not just documented.

## 1. Zero disk, ever

No platform touches any persistent storage API for scan data:

| Platform | Storage APIs never used for scan data |
|---|---|
| iOS | `FileManager`, `UserDefaults`, disk caches |
| Android | internal/external storage, `SharedPreferences`, disk caches |
| Web | `localStorage`, `sessionStorage`, `IndexedDB`, Cache API, cookies |
| .NET / Java | any file write, any serialization of a PAN type |

None of the PAN-carrying types (`SecurePAN`, `SecurePan`, the JS
`Uint8Array`) implement a serialization protocol (`Codable`, `Serializable`,
`Parcelable`, JSON attributes) — this is deliberate, so a PAN can't end up
persisted *by accident* through a generic serialize-everything code path
elsewhere in a host app.

## 2. No logging of sensitive data

Every PAN-carrying type redacts itself:

- Swift `SecurePAN.description`/`debugDescription`/`customMirror` →
  `SecurePAN(visa, ****1234)`
- Kotlin/Java `SecurePan.toString()` → `SecurePan(VISA, ****1234)`
- C# `SecurePan.ToString()` → `SecurePan(Visa, ****1234)`

Redaction is a safety net, not the primary control — the primary control is
that no SDK code path calls `print`/`Log`/`Console.WriteLine`/`console.*`
with a PAN or raw pixel data in the first place. `ScannerError`/
`FintechCoreException`/`ScanEvent.Error` carry only status codes or
non-sensitive diagnostic strings, so they're safe to log as-is.

## 3. Deterministic wipe, not GC-dependent

Every PAN buffer is wiped **synchronously**, right after the result
callback returns — not "eventually, when the GC gets to it":

| Platform | Mechanism |
|---|---|
| C++ core | `aegis_secure_zero` — a volatile write loop the compiler cannot optimize away |
| Swift | `SecurePAN.wipe()` calls `aegis_secure_zero` on heap-allocated storage; `deinit` calls it again as a safety net |
| Kotlin | `SecurePan.close()` fills the backing `CharArray`; used with `use { }` |
| C# | `SecurePan` buffer is allocated on the **Pinned Object Heap** (`GC.AllocateArray<char>(pinned: true)`) so the GC can never relocate/copy it, then wiped with `CryptographicOperations.ZeroMemory` (JIT-safe) |
| Java | `char[]` wiped with `Arrays.fill('\0')`; native side additionally zeroed via `aegis_secure_zero` before its `Arena` closes |
| Web | `Uint8Array.fill(0)` on both the worker's copy and the transferred buffer, immediately after `onCard` returns |

The "wipe after callback" ordering is the same on every platform: the SDK
calls your listener/delegate/callback **while the buffer is still valid**,
then wipes it the instant that call returns. If you need the digits beyond
that point (e.g. to call a tokenizer), the call must happen synchronously
inside the callback — see each platform doc's "What to copy into your app"
notes, or the samples under `sample/`.

## 4. Multi-frame confirmation (reduces false positives, not a security control per se)

All three camera-facing platforms (iOS/Android/Web) require the *same*
extracted PAN to appear in `requiredConsecutiveCardHits` (default 2)
consecutive analyzed frames before reporting it. This isn't PCI-DSS scope
reduction by itself, but it directly reduces how often a single OCR
misread could surface a wrong (or Luhn-accidentally-valid) number to your
app — fewer false detections means fewer PANs your integration code has to
reason about at all.

## 5. Last 4 digits are the one safe thing to display

PCI-DSS 3.4 (truncation) permits displaying the last four digits of a PAN.
Every `SecurePAN`/`SecurePan` type computes and exposes `last4` as a plain
`String` *at construction time*, before the buffer is ever wiped — it's the
one piece of card data in this SDK you're meant to hold onto and display
(e.g. in a receipt UI), everything else should stay inside the callback.

## 6. Expiry and cardholder name are best-effort, not wiped

`expiry`/`cardholderName` (every platform's `SecurePAN`/`SecurePan`/`CardScan`
type) are scanned from the same OCR text as the PAN but have no checksum
backing them the way Luhn backs the PAN — treat them as display hints, not
validated data. Neither is PCI-DSS cardholder data *on its own* (expiry
alone, or a name alone, isn't sensitive the way a PAN is), so unlike the PAN
digits they are **not** wiped by `wipe()`/`close()`/`Dispose()`. They only
become sensitive in combination with the PAN — which is wiped — so don't
independently cache or log `cardholderName` alongside a PAN you captured
earlier in the same session.

## 7. What this SDK does NOT do for you

AegisScan performs capture, OCR repair, Luhn validation, and brand
detection — it does not tokenize, encrypt, transmit, or store the PAN for
you. Reducing your application's PCI-DSS scope further (e.g. to SAQ
A/A-EP) requires sending the PAN to a tokenization service or payment
gateway **from inside the callback**, synchronously, and never holding a
copy of it anywhere else in your own code. The samples under `sample/`
mark the exact line where that call belongs with a comment.
