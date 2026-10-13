# Core Engine

Source: `core/include/aegisscan/fintech_core.hpp` (namespace `aegisscan::core`),
exposed to other languages through `core/include/aegisscan/fintech_core_c.h`
+ `core/src/fintech_core_c.cpp`.

The whole engine is header-only C++20, has no external dependencies, does
no dynamic allocation inside hot loops (`std::string_view` and
`std::vector::reserve` throughout), and ships its GoogleTest suite inline
in the same header.

## LuhnValidator

```cpp
bool LuhnValidator::Validate(std::string_view number);
std::optional<CardNumber> LuhnValidator::RepairAndExtract(std::string_view raw_ocr);
```

**`Validate`**: standard Luhn Mod-10 — double every second digit from the
right, subtract 9 if the result exceeds 9, sum, check divisibility by 10.

**`RepairAndExtract`** runs a four-stage pipeline over raw OCR text:

1. **Tokenize** on separators. `' '`, `'-'`, `'.'`, `'\t'` are *group*
   separators — they join tokens into one candidate run (so
   `"4111 1111 1111 1111"` is treated as one 16-digit candidate). Anything
   else non-alphanumeric (newlines, punctuation) is a *hard break* — it
   flushes the current run entirely, so digits from different printed lines
   or fields are never glued together.
2. **Repair** each character through a fixed OCR-confusion map:
   `O/D/Q/o → 0`, `I/l/|/i → 1`, `Z/z → 2`, `S/s/$ → 5`, `B → 8`, `A → 4`.
   A character that isn't a digit and isn't in this map invalidates its
   token (not the whole run).
3. **Guard against over-repair**: a run is only considered if at least half
   its characters were *already* digits before repair
   (`real_digits * 2 >= run_size`). This is deliberate — the engine fixes a
   handful of OCR misreads, it does not try to "rescue" a string that's
   mostly garbage, since that would start producing false positives.
4. **Extract and validate**: for each token-boundary-aligned substring of
   length 13–19 (checked in the order 16, 15, 19, 14, 13, 17, 18 — most
   common card lengths first), check its BIN/IIN prefix against the known
   ranges below, then run `Validate`. The first candidate that passes both
   checks wins.

### Brand / BIN ranges

| Brand | Rule |
|---|---|
| Visa | starts with `4`, length 13, 16, or 19 |
| Mastercard | length 16, first two digits 51–55, or first four digits 2221–2720 |
| Amex | length 15, first two digits 34 or 37 |
| JCB | length ≥ 16, first four digits 3528–3589 |

A candidate with no matching brand is rejected even if it passes Luhn —
this is what keeps the extractor from matching on an arbitrary 16-digit
number embedded in unrelated text (an order number, a phone number, etc).

### `CardNumber`

The validated PAN is returned in `CardNumber`, a fixed-capacity buffer
(never `std::string`), so the one copy of the digits can be wiped
deterministically. Every platform binding mirrors this as `SecurePAN`
(Swift), `SecurePan` (Kotlin/Java/C#), or a transferred `Uint8Array`
(TypeScript) — see [Security & PCI-DSS](07-security-pci-dss.md).

### Expiry and cardholder name

```cpp
std::optional<ExpiryDate> extract_expiry(std::string_view raw_ocr);
std::optional<std::string> extract_cardholder_name(std::string_view raw_ocr);
```

Both are best-effort, scanned from the same raw OCR text as the PAN, but
neither has a checksum to validate against the way `Validate` backs the
PAN — a transaction reference that happens to look like `12/29`, or an
unrelated all-caps line, can't be told apart from the real thing with
certainty. That also means neither is PCI-DSS cardholder data on its own,
so unlike `CardNumber` they aren't wiped — every platform's combined
extraction (`extractCardDetails` / `extractCardDetails` / `ExtractCardDetails`)
attaches them to the same wipeable PAN type as plain, unwiped fields.

- **`extract_expiry`** scans for the first `MM/YY` or `MM/YYYY` pattern
  (OCR-repairing the digits around the `/` the same way PAN extraction
  does), validates the month is `01`–`12`, and returns the first match in
  reading order.
- **`extract_cardholder_name`** returns the longest line that's all
  uppercase letters/spaces, has 2+ words, and doesn't match a small
  blocklist of brand/product words (`VISA`, `GOLD`, `VALID`, `THRU`, …) —
  "looks like a name and isn't a known non-name word" is the only signal
  available.

## SpatialMatcher

```cpp
std::vector<MatchResult> SpatialMatcher::Match(
    std::span<const BarcodeItem> barcodes,
    std::span<const TextItem> texts);
```

Associates voucher barcodes with the nearby printed text that labels them
(e.g. a receipt's barcode and the "VOUCHER 099123456789" line above it).

1. **Normalize**: compute a bounding frame (either the caller-supplied
   `frame_width`/`frame_height`, or the union of all box extents) and
   express every box center in that frame's coordinates, so the same
   distance thresholds work regardless of actual image resolution.
2. **Filter candidates**: a barcode/text pair is only considered if (a) the
   gap between their boxes is within `max_gap_ratio × max(height)`, and
   (b) the text contains a long-enough run of characters matching the
   barcode payload (`longest common substring ≥ min_overlap_chars`, after
   uppercasing and running the *same* OCR-repair map used by
   `LuhnValidator` on both strings). This second check is what prevents the
   matcher from pairing a barcode with unrelated nearby text.
3. **Score and classify alignment**: `score = normalized_center_distance ×
   alignment_weight × (1 − 0.5 × overlap / min(len))`. Alignment is one of
   `kTextBelow`, `kTextAbove`, `kOverlapping` (all weighted favorably via
   `stacked_weight`, default `0.5`), `kSideBySide` (`side_by_side_weight`,
   default `1.0`), or `kDiagonal` (`diagonal_weight`, default `1.5`,
   i.e. penalized). Vertically stacked pairs — how a barcode and its label
   are normally printed — win over side-by-side or diagonal pairs at the
   same distance.
4. **Assign greedily, one-to-one**: sort all candidate pairs by score, then
   walk them taking the best remaining pair for each barcode/text, so one
   barcode is never matched to two labels or vice versa.

All of `SpatialMatcherOptions`'s defaults (`max_gap_ratio = 2`,
`min_axis_overlap = 0.3`, `min_overlap_chars = 4`, the three alignment
weights above) are reproduced identically in the Kotlin, TypeScript, Swift,
C#, and Java layers — see each platform doc for the exact type name.

## FrameAnalyzer

```cpp
FrameStatus FrameAnalyzer::Validate(const LumaFrame& frame) const;
FrameMetrics FrameAnalyzer::Analyze(const LumaFrame& frame) const;
```

Defensive, read-only checks on a single 8-bit luma plane, used by every
camera-facing platform before running any Vision/ML Kit request:

| `FrameStatus` | Meaning |
|---|---|
| `kOk` | frame is usable |
| `kNullBuffer` | missing plane / fewer planes than expected |
| `kInvalidDimensions` | width/height out of `[64, 16384]` |
| `kInvalidStride` | row stride smaller than `width × pixel_stride` |
| `kBufferTooSmall` | buffer capacity less than `stride × (height-1) + width` |
| `kUnsupportedAspectRatio` | outside the configured min/max aspect ratio |
| `kTooDark` / `kTooBright` | mean luma outside the configured range |
| `kBlurry` | focus score (gradient-based) below threshold |

This is pure arithmetic over pointers/lengths the caller already owns —
no copy, no allocation — so it's cheap enough to run on every single frame
before spending inference time on it.

## C ABI surface (`fintech_core_c.h`)

Every binding above ultimately calls these functions:

```c
const char* aegis_version(void);
int32_t aegis_luhn_validate(const char* digits, int32_t len);
int32_t aegis_extract_pan(const char* raw_ocr, int32_t raw_len,
                           char* out_digits, int32_t out_capacity,
                           int32_t* out_len, int32_t* out_brand);
int32_t aegis_extract_pan_utf16(const char16_t* raw_ocr, int32_t raw_len,
                                 char16_t* out_digits, int32_t out_capacity,
                                 int32_t* out_len, int32_t* out_brand);
int32_t aegis_extract_expiry(const char* raw_ocr, int32_t raw_len,
                              aegis_expiry* out_expiry);
int32_t aegis_extract_cardholder_name(const char* raw_ocr, int32_t raw_len,
                                        char* out_name, int32_t out_capacity,
                                        int32_t* out_len);
int32_t aegis_analyze_luma(const uint8_t* data, int64_t data_len,
                            int32_t width, int32_t height, int32_t row_stride,
                            aegis_frame_metrics* out_metrics);
aegis_matcher_t* aegis_matcher_create(float frame_width, float frame_height);
void aegis_matcher_destroy(aegis_matcher_t* matcher);
int32_t aegis_matcher_match(aegis_matcher_t* matcher,
                             const aegis_item* barcodes, int32_t barcode_count,
                             const aegis_item* texts, int32_t text_count,
                             aegis_match* out, int32_t out_capacity, int32_t* out_count);
void aegis_secure_zero(void* data, int64_t len);
```

`aegis_secure_zero` is a volatile, compiler-fence-protected zero — every
`wipe()`/`close()`/`Dispose()` across every platform ultimately calls
through to it (directly, or via the platform's own equivalent) so the JIT
or optimizer can never elide the wipe.
