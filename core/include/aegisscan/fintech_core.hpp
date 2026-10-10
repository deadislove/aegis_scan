// Copyright 2026 AegisScan Authors. All rights reserved.
//
// fintech_core.hpp -- AegisScan cross-platform core engine (header-only).
//
//   * LuhnValidator  : Luhn Mod-10, OCR character repair, BIN detection and
//                      allocation-free PAN extraction from raw OCR text.
//   * SpatialMatcher : 2D association of barcodes with nearby text labels
//                      (normalized center distance + vertical alignment +
//                      payload/text overlap).
//   * FrameAnalyzer  : defensive validation of luma (Y-plane) buffers
//                      (dimensions, stride, size, aspect ratio, exposure,
//                      focus) before any expensive inference is attempted.
//
// Design rules
//   * Pure C++20, no external dependencies, no I/O, no logging. Nothing in
//     this file ever touches the file system (PCI-DSS zero-disk guarantee).
//   * Every public function is either stateless or `const` and therefore
//     safe to call concurrently from any number of threads. Mutable scratch
//     memory (SpatialMatcher::Scratch) is owned by the caller, one per thread.
//   * Hot paths (PAN extraction, LCS overlap, Luhn) run exclusively on fixed
//     size stack buffers. Buffers that held PAN digits are wiped with
//     SecureZero() before they go out of scope.
//
// Naming follows the Google C++ Style Guide, except for `validate`,
// `repair_and_extract`, `extract_card`, and `match_barcodes_with_text`,
// which keep snake_case so every platform binding exposes identical names.
//
// Unit tests: define AEGISSCAN_FINTECH_CORE_ENABLE_TESTS in exactly ONE
// translation unit that links against GoogleTest (see core/tests/).

#ifndef AEGISSCAN_FINTECH_CORE_HPP_
#define AEGISSCAN_FINTECH_CORE_HPP_

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>

namespace aegisscan::core {

inline constexpr std::string_view kVersion = "1.0.0";

inline constexpr std::size_t kMinPanLength = 13;
inline constexpr std::size_t kMaxPanLength = 19;

// Overwrites `size` bytes at `data` with zeros. The volatile access plus the
// signal fence prevent the compiler from eliding the stores as dead writes.
inline void SecureZero(void* data, std::size_t size) noexcept {
  if (data == nullptr) return;
  volatile unsigned char* p = static_cast<volatile unsigned char*>(data);
  while (size-- > 0) *p++ = 0;
  std::atomic_signal_fence(std::memory_order_seq_cst);
}

// ---------------------------------------------------------------------------
// Card brands
// ---------------------------------------------------------------------------

enum class CardBrand : std::uint8_t {
  kUnknown = 0,
  kVisa = 1,
  kMastercard = 2,
  kAmex = 3,
  kJcb = 4,
};

constexpr std::string_view CardBrandName(CardBrand brand) noexcept {
  switch (brand) {
    case CardBrand::kVisa:
      return "visa";
    case CardBrand::kMastercard:
      return "mastercard";
    case CardBrand::kAmex:
      return "amex";
    case CardBrand::kJcb:
      return "jcb";
    case CardBrand::kUnknown:
      break;
  }
  return "unknown";
}

namespace internal {

constexpr bool IsAsciiDigit(char c) noexcept { return c >= '0' && c <= '9'; }

// Maps a raw OCR character to the digit it most likely represents.
// Returns '\0' when the character cannot be part of a card number.
//   O D Q o -> 0 | I l | i -> 1 | Z z -> 2 | S s $ -> 5 | B -> 8 | A -> 4
constexpr char RepairOcrChar(char c) noexcept {
  if (IsAsciiDigit(c)) return c;
  switch (c) {
    case 'O':
    case 'D':
    case 'Q':
    case 'o':
      return '0';
    case 'I':
    case 'l':
    case '|':
    case 'i':
      return '1';
    case 'Z':
    case 'z':
      return '2';
    case 'S':
    case 's':
    case '$':
      return '5';
    case 'B':
      return '8';
    case 'A':
      return '4';
    default:
      return '\0';
  }
}

// Separators between digit groups *within* one printed card number.
constexpr bool IsGroupSeparator(char c) noexcept {
  return c == ' ' || c == '-' || c == '.' || c == '\t';
}

// Characters that terminate a candidate number entirely (new line, field
// delimiters such as "No:" or "12/28", masking stars, ...).
constexpr bool IsHardBreak(char c) noexcept {
  switch (c) {
    case '\n':
    case '\r':
    case '\v':
    case '\f':
    case ':':
    case ';':
    case ',':
    case '/':
    case '\\':
    case '#':
    case '(':
    case ')':
    case '[':
    case ']':
    case '{':
    case '}':
    case '*':
    case '=':
    case '"':
    case '\'':
      return true;
    default:
      return false;
  }
}

// Integer value of the first `n` (<= 9) characters of an all-digit view.
constexpr int DigitPrefix(std::string_view digits, std::size_t n) noexcept {
  int value = 0;
  for (std::size_t i = 0; i < n && i < digits.size(); ++i) {
    value = value * 10 + (digits[i] - '0');
  }
  return value;
}

}  // namespace internal

// Identifies the card scheme from an all-digit PAN (IIN/BIN ranges + length).
//   Visa       : 4                      , 13 / 16 / 19 digits
//   Mastercard : 51-55, 2221-2720       , 16 digits
//   Amex       : 34, 37                 , 15 digits
//   JCB        : 3528-3589              , 16-19 digits
constexpr CardBrand DetectBrand(std::string_view digits) noexcept {
  const std::size_t n = digits.size();
  if (n < kMinPanLength || n > kMaxPanLength) return CardBrand::kUnknown;
  for (char c : digits) {
    if (!internal::IsAsciiDigit(c)) return CardBrand::kUnknown;
  }
  const int p1 = internal::DigitPrefix(digits, 1);
  const int p2 = internal::DigitPrefix(digits, 2);
  const int p4 = internal::DigitPrefix(digits, 4);
  if (p1 == 4 && (n == 13 || n == 16 || n == 19)) return CardBrand::kVisa;
  if (n == 16 && ((p2 >= 51 && p2 <= 55) || (p4 >= 2221 && p4 <= 2720))) {
    return CardBrand::kMastercard;
  }
  if (n == 15 && (p2 == 34 || p2 == 37)) return CardBrand::kAmex;
  if (n >= 16 && p4 >= 3528 && p4 <= 3589) return CardBrand::kJcb;
  return CardBrand::kUnknown;
}

// ---------------------------------------------------------------------------
// CardNumber -- self-wiping, fixed-capacity PAN value type (no heap).
// ---------------------------------------------------------------------------

class CardNumber final {
 public:
  CardNumber() noexcept = default;

  // Precondition: `digits` holds 1..kMaxPanLength ASCII digits. Longer input
  // is truncated defensively rather than overflowing.
  CardNumber(std::string_view digits, CardBrand brand) noexcept
      : brand_(brand) {
    const std::size_t n = std::min(digits.size(), kMaxPanLength);
    std::copy_n(digits.data(), n, digits_.data());
    size_ = static_cast<std::uint8_t>(n);
  }

  CardNumber(const CardNumber&) noexcept = default;
  CardNumber& operator=(const CardNumber&) noexcept = default;

  CardNumber(CardNumber&& other) noexcept
      : digits_(other.digits_), size_(other.size_), brand_(other.brand_) {
    other.Wipe();
  }

  CardNumber& operator=(CardNumber&& other) noexcept {
    if (this != &other) {
      digits_ = other.digits_;
      size_ = other.size_;
      brand_ = other.brand_;
      other.Wipe();
    }
    return *this;
  }

  ~CardNumber() { Wipe(); }

  // View into the internal buffer; invalidated by Wipe() / destruction.
  std::string_view digits() const noexcept {
    return std::string_view(digits_.data(), size_);
  }
  std::size_t size() const noexcept { return size_; }
  bool empty() const noexcept { return size_ == 0; }
  CardBrand brand() const noexcept { return brand_; }

  // Last four digits: the only part of a PAN that may be displayed.
  std::string_view last4() const noexcept {
    return size_ < 4 ? std::string_view()
                     : std::string_view(digits_.data() + size_ - 4, 4);
  }

  void Wipe() noexcept {
    SecureZero(digits_.data(), digits_.size());
    size_ = 0;
    brand_ = CardBrand::kUnknown;
  }

 private:
  std::array<char, kMaxPanLength + 1> digits_{};
  std::uint8_t size_ = 0;
  CardBrand brand_ = CardBrand::kUnknown;
};

namespace internal {

// One "run" = consecutive numeric-looking tokens separated only by group
// separators, i.e. a candidate printed card number. Fixed capacity, wiped
// on reset and destruction.
class PanRun final {
 public:
  static constexpr std::size_t kCapacity = 64;
  static constexpr std::size_t kMaxGroups = 32;

  PanRun() noexcept = default;
  PanRun(const PanRun&) = delete;
  PanRun& operator=(const PanRun&) = delete;
  ~PanRun() { SecureZero(digits_.data(), digits_.size()); }

  bool CanAppend(std::size_t token_size) const noexcept {
    return size_ + token_size <= kCapacity && groups_ < kMaxGroups;
  }

  void Append(std::string_view token, std::size_t real_digits) noexcept {
    std::copy(token.begin(), token.end(), digits_.begin() + size_);
    size_ += token.size();
    real_digits_ += real_digits;
    ++groups_;
    bounds_[groups_] = static_cast<std::uint8_t>(size_);
    if (token.size() > kMaxPanLength) has_long_group_ = true;
  }

  void Reset() noexcept {
    SecureZero(digits_.data(), size_);
    size_ = 0;
    groups_ = 0;
    real_digits_ = 0;
    has_long_group_ = false;
  }

  // Returns the first valid PAN of this run in reading order. Candidates are
  // aligned to printed group boundaries, so "Ref 1234 4111 1111 1111 1111"
  // yields the card and not a shifted window. Runs consisting mostly of
  // repaired letters (e.g. the word "BOSS") are rejected.
  std::optional<CardNumber> Evaluate() const noexcept;

 private:
  bool IsBoundary(std::size_t pos, std::size_t from_group) const noexcept {
    for (std::size_t g = from_group; g <= groups_; ++g) {
      if (bounds_[g] == pos) return true;
      if (bounds_[g] > pos) return false;
    }
    return false;
  }

  std::array<char, kCapacity> digits_{};
  std::array<std::uint8_t, kMaxGroups + 1> bounds_{};  // bounds_[0] == 0.
  std::size_t size_ = 0;
  std::size_t groups_ = 0;
  std::size_t real_digits_ = 0;
  bool has_long_group_ = false;
};

// Preferred candidate lengths: canonical lengths of the supported schemes
// first, so a trailing CVV/expiry group is not glued onto a valid PAN.
inline constexpr std::array<std::uint8_t, 7> kPanLengthPreference = {
    16, 15, 19, 14, 13, 17, 18};

}  // namespace internal

// ---------------------------------------------------------------------------
// LuhnValidator
// ---------------------------------------------------------------------------

class LuhnValidator final {
 public:
  LuhnValidator() = delete;

  // Standard Luhn Mod-10 check. Requires >= 2 characters, digits only.
  static constexpr bool validate(std::string_view number) noexcept {
    if (number.size() < 2) return false;
    int sum = 0;
    bool double_it = false;
    for (std::size_t i = number.size(); i-- > 0;) {
      const char c = number[i];
      if (!internal::IsAsciiDigit(c)) return false;
      int d = c - '0';
      if (double_it) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      double_it = !double_it;
    }
    return sum % 10 == 0;
  }

  // Allocation-free extraction: repairs OCR confusions, splits the input
  // into candidate runs, and returns the first 13-19 digit candidate that has
  // a supported BIN and passes Luhn. Preferred API (result self-wipes).
  static std::optional<CardNumber> extract_card(
      std::string_view raw_ocr) noexcept {
    return ExtractImpl(raw_ocr);
  }

  // UTF-16 overload (zero-copy path for .NET / JVM strings). Code units
  // outside ASCII are treated as ordinary non-digit letters.
  static std::optional<CardNumber> extract_card(
      std::u16string_view raw_ocr) noexcept {
    return ExtractImpl(raw_ocr);
  }

  // Returns the repaired PAN as std::string.
  // NOTE: std::string may relocate its buffer; callers handling live PANs
  // should prefer extract_card() or wipe the string via SecureZero().
  static std::optional<std::string> repair_and_extract(
      std::string_view raw_ocr) {
    std::optional<CardNumber> card = extract_card(raw_ocr);
    if (!card) return std::nullopt;
    return std::string(card->digits());
  }

 private:
  template <typename CharT>
  static constexpr char Narrow(CharT unit) noexcept {
    const auto value = static_cast<std::make_unsigned_t<CharT>>(unit);
    return value < 0x80 ? static_cast<char>(value) : '~';
  }

  template <typename CharT>
  static std::optional<CardNumber> ExtractImpl(
      std::basic_string_view<CharT> raw_ocr) noexcept {
    internal::PanRun run;
    std::array<char, internal::PanRun::kCapacity> token{};
    std::size_t token_size = 0;
    std::size_t token_real = 0;
    bool token_valid = true;
    bool token_started = false;
    std::optional<CardNumber> found;

    auto flush_run = [&]() noexcept {
      if (!found) found = run.Evaluate();
      run.Reset();
    };
    auto flush_token = [&]() noexcept {
      if (token_started) {
        if (token_valid) {
          if (!run.CanAppend(token_size)) flush_run();
          run.Append(std::string_view(token.data(), token_size), token_real);
        } else {
          flush_run();  // A non-numeric word ends the candidate number.
        }
      }
      SecureZero(token.data(), token_size);
      token_size = 0;
      token_real = 0;
      token_valid = true;
      token_started = false;
    };

    for (std::size_t i = 0; i <= raw_ocr.size() && !found; ++i) {
      const char c = i < raw_ocr.size() ? Narrow(raw_ocr[i]) : '\n';
      if (internal::IsGroupSeparator(c)) {
        flush_token();
        continue;
      }
      if (internal::IsHardBreak(c)) {
        flush_token();
        flush_run();
        continue;
      }
      token_started = true;
      if (!token_valid) continue;
      const char repaired = internal::RepairOcrChar(c);
      if (repaired == '\0' || token_size >= token.size()) {
        token_valid = false;
        continue;
      }
      token[token_size++] = repaired;
      if (internal::IsAsciiDigit(c)) ++token_real;
    }
    SecureZero(token.data(), token.size());
    return found;
  }
};

inline std::optional<CardNumber> internal::PanRun::Evaluate() const noexcept {
  if (size_ < kMinPanLength) return std::nullopt;
  if (real_digits_ * 2 < size_) return std::nullopt;  // Mostly letters.
  const std::string_view all(digits_.data(), size_);

  auto try_candidate = [&](std::size_t start,
                           std::size_t len) -> std::optional<CardNumber> {
    const std::string_view candidate = all.substr(start, len);
    const CardBrand brand = DetectBrand(candidate);
    if (brand != CardBrand::kUnknown && LuhnValidator::validate(candidate)) {
      return CardNumber(candidate, brand);
    }
    return std::nullopt;
  };

  for (std::size_t g = 0; g < groups_; ++g) {
    const std::size_t start = bounds_[g];
    for (const std::uint8_t len : kPanLengthPreference) {
      const std::size_t end = start + len;
      if (end > size_ || !IsBoundary(end, g + 1)) continue;
      if (auto card = try_candidate(start, len)) return card;
    }
  }
  // Fallback for glued numbers (no printed separators, > 19 digits).
  if (has_long_group_) {
    for (std::size_t start = 0; start + kMinPanLength <= size_; ++start) {
      for (const std::uint8_t len : kPanLengthPreference) {
        if (start + len > size_) continue;
        if (auto card = try_candidate(start, len)) return card;
      }
    }
  }
  return std::nullopt;
}

// ---------------------------------------------------------------------------
// SpatialMatcher
// ---------------------------------------------------------------------------

// Axis-aligned box, top-left origin, y grows downwards (image coordinates).
struct BoundingBox {
  float x;
  float y;
  float width;
  float height;
};

struct BarcodeItem {
  std::string payload;
  BoundingBox box;
};

struct TextItem {
  std::string text;
  BoundingBox box;
};

struct MatchResult {
  BarcodeItem barcode;
  std::string associated_text;
};

// Non-owning view used by the allocation-free matching core and the C ABI.
struct ItemView {
  std::string_view text;
  BoundingBox box;
};

enum class Alignment : std::uint8_t {
  kTextBelow = 0,    // Text stacked under the barcode (most common).
  kTextAbove = 1,    // Text stacked over the barcode.
  kOverlapping = 2,  // Text drawn inside / across the barcode area.
  kSideBySide = 3,   // Same row, left or right.
  kDiagonal = 4,     // Neither column nor row aligned.
};

struct MatchPair {
  std::uint32_t barcode_index;
  std::uint32_t text_index;
  float score;                // Lower is better.
  float normalized_distance;  // Center distance in normalized coordinates.
  std::uint32_t overlap_chars;
  Alignment alignment;
};

struct SpatialMatcherOptions {
  // Frame size used to normalize centers to [0,1]. When <= 0 the union of all
  // input boxes is used as the reference frame.
  float frame_width = 0.0f;
  float frame_height = 0.0f;
  // Maximum edge-to-edge gap, expressed in multiples of the taller box.
  float max_gap_ratio = 2.0f;
  // Fraction of the narrower box that must overlap on the shared axis for
  // the pair to count as column (stacked) or row (side-by-side) aligned.
  float min_axis_overlap = 0.3f;
  // Score multipliers (lower = preferred). Vertical stacking is preferred.
  float stacked_weight = 0.5f;
  float side_by_side_weight = 1.0f;
  float diagonal_weight = 1.5f;
  // Minimum number of shared characters between payload and text (clamped to
  // the shorter of the two folded strings).
  std::uint32_t min_overlap_chars = 4;
};

namespace internal {

inline constexpr std::size_t kMaxFoldedLength = 128;

inline bool IsValidBox(const BoundingBox& b) noexcept {
  return std::isfinite(b.x) && std::isfinite(b.y) && std::isfinite(b.width) &&
         std::isfinite(b.height) && b.width > 0.0f && b.height > 0.0f;
}

// Upper-cases, drops non-alphanumerics and folds OCR-confusable letters to
// digits on both sides, so "AB-1O5" and "AB 105" compare equal.
inline std::size_t FoldForCompare(
    std::string_view in, std::array<char, kMaxFoldedLength>& out) noexcept {
  std::size_t n = 0;
  for (char c : in) {
    if (n == out.size()) break;
    if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 'a' + 'A');
    const bool alnum = IsAsciiDigit(c) || (c >= 'A' && c <= 'Z');
    if (!alnum) continue;
    const char repaired = RepairOcrChar(c);
    out[n++] = repaired != '\0' ? repaired : c;
  }
  return n;
}

// Longest common substring length via a rolling DP row on the stack.
inline std::size_t LongestCommonSubstring(std::string_view a,
                                          std::string_view b) noexcept {
  if (a.empty() || b.empty()) return 0;
  std::array<std::uint16_t, kMaxFoldedLength + 1> prev{};
  std::array<std::uint16_t, kMaxFoldedLength + 1> curr{};
  std::size_t best = 0;
  for (std::size_t i = 1; i <= a.size(); ++i) {
    for (std::size_t j = 1; j <= b.size(); ++j) {
      curr[j] = a[i - 1] == b[j - 1]
                    ? static_cast<std::uint16_t>(prev[j - 1] + 1)
                    : std::uint16_t{0};
      best = std::max<std::size_t>(best, curr[j]);
    }
    prev.swap(curr);
    curr[0] = 0;
  }
  return best;
}

inline float AxisOverlap(float a0, float a1, float b0, float b1) noexcept {
  const float overlap = std::min(a1, b1) - std::max(a0, b0);
  const float shorter = std::min(a1 - a0, b1 - b0);
  return (overlap <= 0.0f || shorter <= 0.0f) ? 0.0f : overlap / shorter;
}

}  // namespace internal

class SpatialMatcher final {
 public:
  // Reusable per-thread scratch memory. Keep one instance alive per camera
  // pipeline: after warm-up, matching performs zero heap allocations.
  struct Scratch {
    std::vector<MatchPair> candidates;
    std::vector<std::uint8_t> barcode_used;
    std::vector<std::uint8_t> text_used;
  };

  SpatialMatcher() noexcept = default;
  explicit SpatialMatcher(const SpatialMatcherOptions& options) noexcept
      : options_(options) {}

  const SpatialMatcherOptions& options() const noexcept { return options_; }

  // Allocation-free core (given warmed-up scratch/out capacity). Computes a
  // greedy one-to-one assignment of barcodes to texts (best score first) and
  // writes it to `out`, sorted by barcode index. Thread-safe for distinct
  // scratch/out objects.
  void MatchIndices(std::span<const ItemView> barcodes,
                    std::span<const ItemView> texts, Scratch& scratch,
                    std::vector<MatchPair>& out) const {
    out.clear();
    scratch.candidates.clear();
    if (barcodes.empty() || texts.empty()) return;

    float origin_x = 0.0f;
    float origin_y = 0.0f;
    float scale = 1.0f;
    ComputeFrame(barcodes, texts, origin_x, origin_y, scale);

    scratch.candidates.reserve(barcodes.size() * texts.size());
    std::array<char, internal::kMaxFoldedLength> folded_payload{};
    std::array<char, internal::kMaxFoldedLength> folded_text{};

    for (std::size_t bi = 0; bi < barcodes.size(); ++bi) {
      const ItemView& bc = barcodes[bi];
      if (!internal::IsValidBox(bc.box)) continue;
      const std::size_t payload_len =
          internal::FoldForCompare(bc.text, folded_payload);
      if (payload_len == 0) continue;
      const std::string_view payload(folded_payload.data(), payload_len);

      for (std::size_t ti = 0; ti < texts.size(); ++ti) {
        const ItemView& tx = texts[ti];
        if (!internal::IsValidBox(tx.box)) continue;
        if (!WithinGap(bc.box, tx.box)) continue;

        const std::size_t text_len =
            internal::FoldForCompare(tx.text, folded_text);
        if (text_len == 0) continue;
        const std::string_view text(folded_text.data(), text_len);
        const std::size_t shorter = std::min(payload_len, text_len);
        const std::size_t required =
            std::min<std::size_t>(options_.min_overlap_chars, shorter);
        const std::size_t overlap =
            internal::LongestCommonSubstring(payload, text);
        if (required == 0 || overlap < required) continue;

        const Alignment alignment = Classify(bc.box, tx.box);
        const float distance =
            NormalizedCenterDistance(bc.box, tx.box, origin_x, origin_y, scale);
        const float overlap_ratio =
            static_cast<float>(overlap) / static_cast<float>(shorter);
        const float score =
            distance * Weight(alignment) * (1.0f - 0.5f * overlap_ratio);

        scratch.candidates.push_back(MatchPair{
            static_cast<std::uint32_t>(bi), static_cast<std::uint32_t>(ti),
            score, distance, static_cast<std::uint32_t>(overlap), alignment});
      }
    }
    SecureZero(folded_payload.data(), folded_payload.size());
    SecureZero(folded_text.data(), folded_text.size());

    std::sort(scratch.candidates.begin(), scratch.candidates.end(),
              [](const MatchPair& a, const MatchPair& b) {
                if (a.score != b.score) return a.score < b.score;
                if (a.barcode_index != b.barcode_index) {
                  return a.barcode_index < b.barcode_index;
                }
                return a.text_index < b.text_index;
              });

    scratch.barcode_used.assign(barcodes.size(), 0);
    scratch.text_used.assign(texts.size(), 0);
    out.reserve(std::min(barcodes.size(), texts.size()));
    for (const MatchPair& pair : scratch.candidates) {
      if (scratch.barcode_used[pair.barcode_index] != 0 ||
          scratch.text_used[pair.text_index] != 0) {
        continue;
      }
      scratch.barcode_used[pair.barcode_index] = 1;
      scratch.text_used[pair.text_index] = 1;
      out.push_back(pair);
    }
    std::sort(out.begin(), out.end(),
              [](const MatchPair& a, const MatchPair& b) {
                return a.barcode_index < b.barcode_index;
              });
  }

  // Owning convenience wrapper over MatchIndices.
  std::vector<MatchResult> match_barcodes_with_text(
      const std::vector<BarcodeItem>& barcodes,
      const std::vector<TextItem>& texts) const {
    std::vector<ItemView> barcode_views;
    std::vector<ItemView> text_views;
    barcode_views.reserve(barcodes.size());
    text_views.reserve(texts.size());
    for (const BarcodeItem& b : barcodes) {
      barcode_views.push_back(ItemView{b.payload, b.box});
    }
    for (const TextItem& t : texts) {
      text_views.push_back(ItemView{t.text, t.box});
    }
    Scratch scratch;
    std::vector<MatchPair> pairs;
    MatchIndices(barcode_views, text_views, scratch, pairs);

    std::vector<MatchResult> results;
    results.reserve(pairs.size());
    for (const MatchPair& p : pairs) {
      results.push_back(
          MatchResult{barcodes[p.barcode_index], texts[p.text_index].text});
    }
    return results;
  }

 private:
  void ComputeFrame(std::span<const ItemView> barcodes,
                    std::span<const ItemView> texts, float& origin_x,
                    float& origin_y, float& scale) const noexcept {
    if (options_.frame_width > 0.0f && options_.frame_height > 0.0f) {
      origin_x = 0.0f;
      origin_y = 0.0f;
      scale = std::max(options_.frame_width, options_.frame_height);
      return;
    }
    float min_x = std::numeric_limits<float>::max();
    float min_y = std::numeric_limits<float>::max();
    float max_x = std::numeric_limits<float>::lowest();
    float max_y = std::numeric_limits<float>::lowest();
    auto extend = [&](const ItemView& item) noexcept {
      if (!internal::IsValidBox(item.box)) return;
      min_x = std::min(min_x, item.box.x);
      min_y = std::min(min_y, item.box.y);
      max_x = std::max(max_x, item.box.x + item.box.width);
      max_y = std::max(max_y, item.box.y + item.box.height);
    };
    for (const ItemView& b : barcodes) extend(b);
    for (const ItemView& t : texts) extend(t);
    if (min_x > max_x) return;  // No valid boxes: keep identity frame.
    origin_x = min_x;
    origin_y = min_y;
    const float extent = std::max(max_x - min_x, max_y - min_y);
    scale = (std::isfinite(extent) && extent > 0.0f) ? extent : 1.0f;
  }

  static float NormalizedCenterDistance(const BoundingBox& a,
                                        const BoundingBox& b, float origin_x,
                                        float origin_y, float scale) noexcept {
    const float ax = (a.x + a.width * 0.5f - origin_x) / scale;
    const float ay = (a.y + a.height * 0.5f - origin_y) / scale;
    const float bx = (b.x + b.width * 0.5f - origin_x) / scale;
    const float by = (b.y + b.height * 0.5f - origin_y) / scale;
    return std::hypot(ax - bx, ay - by);
  }

  bool WithinGap(const BoundingBox& a, const BoundingBox& b) const noexcept {
    const float gap_x =
        std::max({0.0f, b.x - (a.x + a.width), a.x - (b.x + b.width)});
    const float gap_y =
        std::max({0.0f, b.y - (a.y + a.height), a.y - (b.y + b.height)});
    const float reference = std::max(a.height, b.height);
    return std::hypot(gap_x, gap_y) <= options_.max_gap_ratio * reference;
  }

  Alignment Classify(const BoundingBox& barcode,
                     const BoundingBox& text) const noexcept {
    const float column = internal::AxisOverlap(
        barcode.x, barcode.x + barcode.width, text.x, text.x + text.width);
    const float row = internal::AxisOverlap(
        barcode.y, barcode.y + barcode.height, text.y, text.y + text.height);
    const bool column_aligned = column >= options_.min_axis_overlap;
    const bool row_aligned = row >= options_.min_axis_overlap;
    if (column_aligned && row_aligned) return Alignment::kOverlapping;
    if (column_aligned) {
      const float barcode_cy = barcode.y + barcode.height * 0.5f;
      const float text_cy = text.y + text.height * 0.5f;
      return text_cy >= barcode_cy ? Alignment::kTextBelow
                                   : Alignment::kTextAbove;
    }
    if (row_aligned) return Alignment::kSideBySide;
    return Alignment::kDiagonal;
  }

  float Weight(Alignment alignment) const noexcept {
    switch (alignment) {
      case Alignment::kTextBelow:
      case Alignment::kTextAbove:
      case Alignment::kOverlapping:
        return options_.stacked_weight;
      case Alignment::kSideBySide:
        return options_.side_by_side_weight;
      case Alignment::kDiagonal:
        break;
    }
    return options_.diagonal_weight;
  }

  SpatialMatcherOptions options_;
};

// Free-function form, using default options.
inline std::vector<MatchResult> match_barcodes_with_text(
    const std::vector<BarcodeItem>& barcodes,
    const std::vector<TextItem>& texts) {
  return SpatialMatcher().match_barcodes_with_text(barcodes, texts);
}

// ---------------------------------------------------------------------------
// FrameAnalyzer -- defensive checks on raw luma buffers.
// ---------------------------------------------------------------------------

enum class FrameStatus : std::uint8_t {
  kOk = 0,
  kNullBuffer = 1,
  kInvalidDimensions = 2,
  kInvalidStride = 3,
  kBufferTooSmall = 4,
  kUnsupportedAspectRatio = 5,
  kTooDark = 6,
  kTooBright = 7,
  kBlurry = 8,
};

struct LumaFrame {
  std::span<const std::uint8_t> data;
  std::int32_t width = 0;
  std::int32_t height = 0;
  std::int32_t row_stride = 0;  // Bytes per row (>= width).
};

struct FrameMetrics {
  FrameStatus status = FrameStatus::kNullBuffer;
  float mean_luma = 0.0f;    // 0..255
  float focus_score = 0.0f;  // Variance of the Laplacian (sampled).
};

struct FrameAnalyzerOptions {
  std::int32_t min_dimension = 64;
  std::int32_t max_dimension = 16384;
  float min_aspect_ratio = 0.25f;  // width / height
  float max_aspect_ratio = 4.0f;
  float min_mean_luma = 20.0f;
  float max_mean_luma = 240.0f;
  float min_focus_score = 0.0f;  // 0 disables; calibrate per device.
  std::int32_t sample_step = 4;  // Pixel stride of the sampling grid.
};

class FrameAnalyzer final {
 public:
  FrameAnalyzer() noexcept = default;
  explicit FrameAnalyzer(const FrameAnalyzerOptions& options) noexcept
      : options_(options) {}

  // Structural validation only (O(1)); never reads pixel memory.
  FrameStatus Validate(const LumaFrame& frame) const noexcept {
    if (frame.data.data() == nullptr || frame.data.empty()) {
      return FrameStatus::kNullBuffer;
    }
    if (frame.width < options_.min_dimension ||
        frame.height < options_.min_dimension ||
        frame.width > options_.max_dimension ||
        frame.height > options_.max_dimension) {
      return FrameStatus::kInvalidDimensions;
    }
    if (frame.row_stride < frame.width) return FrameStatus::kInvalidStride;
    const std::size_t required =
        static_cast<std::size_t>(frame.row_stride) *
            static_cast<std::size_t>(frame.height - 1) +
        static_cast<std::size_t>(frame.width);
    if (frame.data.size() < required) return FrameStatus::kBufferTooSmall;
    const float aspect =
        static_cast<float>(frame.width) / static_cast<float>(frame.height);
    if (aspect < options_.min_aspect_ratio ||
        aspect > options_.max_aspect_ratio) {
      return FrameStatus::kUnsupportedAspectRatio;
    }
    return FrameStatus::kOk;
  }

  // Validation + sampled exposure / focus metrics. Read-only, no allocation.
  FrameMetrics Analyze(const LumaFrame& frame) const noexcept {
    FrameMetrics metrics;
    metrics.status = Validate(frame);
    if (metrics.status != FrameStatus::kOk) return metrics;

    const std::int32_t step = std::max<std::int32_t>(1, options_.sample_step);
    const std::uint8_t* base = frame.data.data();
    const std::size_t stride = static_cast<std::size_t>(frame.row_stride);
    double luma_sum = 0.0;
    double lap_sum = 0.0;
    double lap_sq_sum = 0.0;
    std::size_t samples = 0;
    for (std::int32_t y = step; y + step < frame.height; y += step) {
      const std::uint8_t* row = base + static_cast<std::size_t>(y) * stride;
      const std::uint8_t* up = row - stride;
      const std::uint8_t* down = row + stride;
      for (std::int32_t x = step; x + step < frame.width; x += step) {
        const int c = row[x];
        const int lap = 4 * c - row[x - 1] - row[x + 1] - up[x] - down[x];
        luma_sum += c;
        lap_sum += lap;
        lap_sq_sum += static_cast<double>(lap) * lap;
        ++samples;
      }
    }
    if (samples == 0) {
      metrics.status = FrameStatus::kInvalidDimensions;
      return metrics;
    }
    const double n = static_cast<double>(samples);
    const double lap_mean = lap_sum / n;
    metrics.mean_luma = static_cast<float>(luma_sum / n);
    metrics.focus_score = static_cast<float>(
        std::max(0.0, lap_sq_sum / n - lap_mean * lap_mean));
    if (metrics.mean_luma < options_.min_mean_luma) {
      metrics.status = FrameStatus::kTooDark;
    } else if (metrics.mean_luma > options_.max_mean_luma) {
      metrics.status = FrameStatus::kTooBright;
    } else if (metrics.focus_score < options_.min_focus_score) {
      metrics.status = FrameStatus::kBlurry;
    }
    return metrics;
  }

 private:
  FrameAnalyzerOptions options_;
};

// Compile-time sanity checks.
static_assert(LuhnValidator::validate("4111111111111111"));
static_assert(!LuhnValidator::validate("4111111111111112"));
static_assert(DetectBrand("378282246310005") == CardBrand::kAmex);
static_assert(DetectBrand("2223003122003222") == CardBrand::kMastercard);
static_assert(internal::RepairOcrChar('B') == '8');
static_assert(internal::RepairOcrChar('O') == '0');

}  // namespace aegisscan::core

// ===========================================================================
// Inline GoogleTest suite (compile in exactly one TU).
// ===========================================================================
#ifdef AEGISSCAN_FINTECH_CORE_ENABLE_TESTS
#include <gtest/gtest.h>

#include <thread>

namespace aegisscan::core {
namespace {

// ---- LuhnValidator::validate ------------------------------------------------

TEST(LuhnValidatorTest, AcceptsKnownTestPans) {
  for (std::string_view pan :
       {"4111111111111111", "4012888888881881", "4222222222222",
        "5555555555554444", "5105105105105100", "2223003122003222",
        "378282246310005", "371449635398431", "3530111333300000",
        "3566002020360505"}) {
    EXPECT_TRUE(LuhnValidator::validate(pan)) << "prefix " << pan.substr(0, 4);
  }
}

TEST(LuhnValidatorTest, RejectsInvalidInput) {
  EXPECT_FALSE(LuhnValidator::validate(""));
  EXPECT_FALSE(LuhnValidator::validate("0"));
  EXPECT_FALSE(LuhnValidator::validate("4111111111111112"));
  EXPECT_FALSE(LuhnValidator::validate("4111 1111 1111 1111"));
  EXPECT_FALSE(LuhnValidator::validate("41111111111111l1"));
  EXPECT_TRUE(LuhnValidator::validate("00"));
}

TEST(LuhnValidatorTest, DetectsBrandsAndLengths) {
  EXPECT_EQ(DetectBrand("4111111111111111"), CardBrand::kVisa);
  EXPECT_EQ(DetectBrand("4222222222222"), CardBrand::kVisa);
  EXPECT_EQ(DetectBrand("411111111111111"), CardBrand::kUnknown);  // 15.
  EXPECT_EQ(DetectBrand("5555555555554444"), CardBrand::kMastercard);
  EXPECT_EQ(DetectBrand("2720990000000007"), CardBrand::kMastercard);
  EXPECT_EQ(DetectBrand("2721000000000000"), CardBrand::kUnknown);
  EXPECT_EQ(DetectBrand("371449635398431"), CardBrand::kAmex);
  EXPECT_EQ(DetectBrand("3530111333300000"), CardBrand::kJcb);
  EXPECT_EQ(DetectBrand("6011111111111117"), CardBrand::kUnknown);
  EXPECT_EQ(DetectBrand("41111111111111a1"), CardBrand::kUnknown);
}

// ---- repair_and_extract: OCR edge cases --------------------------------------

TEST(OcrRepairTest, PlainAndGroupedNumbers) {
  EXPECT_EQ(LuhnValidator::repair_and_extract("4111111111111111"),
            "4111111111111111");
  EXPECT_EQ(LuhnValidator::repair_and_extract("4111 1111 1111 1111"),
            "4111111111111111");
  EXPECT_EQ(LuhnValidator::repair_and_extract("3782-822463-10005"),
            "378282246310005");
}

TEST(OcrRepairTest, EightMisreadAsB) {
  EXPECT_EQ(LuhnValidator::repair_and_extract("4012 B888 88B8 1881"),
            "4012888888881881");
  EXPECT_EQ(LuhnValidator::repair_and_extract("3782 B22463 10005"),
            "378282246310005");
}

TEST(OcrRepairTest, ZeroMisreadAsLetterODQ) {
  EXPECT_EQ(LuhnValidator::repair_and_extract("4O12 8888 8888 1881"),
            "4012888888881881");
  EXPECT_EQ(LuhnValidator::repair_and_extract("51O5 1D51 O51Q 51OO"),
            "5105105105105100");
  EXPECT_EQ(LuhnValidator::repair_and_extract("3530 1113 3330 OoOO"),
            "3530111333300000");
}

TEST(OcrRepairTest, OneFiveTwoFourMisreads) {
  EXPECT_EQ(LuhnValidator::repair_and_extract("4lI1 |111 1i11 1111"),
            "4111111111111111");
  EXPECT_EQ(LuhnValidator::repair_and_extract("5555 SSSS 5555 4444"),
            "5555555555554444");
  EXPECT_EQ(LuhnValidator::repair_and_extract("$555 5555 5555 4444"),
            "5555555555554444");
  EXPECT_EQ(LuhnValidator::repair_and_extract("Z223 0031 Z200 3222"),
            "2223003122003222");
  EXPECT_EQ(LuhnValidator::repair_and_extract("A111 1111 1111 1111"),
            "4111111111111111");
}

TEST(OcrRepairTest, ExtractsFromNoisyCardText) {
  EXPECT_EQ(LuhnValidator::repair_and_extract(
                "BANK OF AEGIS\nCard No: 4111 1111 1111 1111\n"
                "VALID THRU 12/28  JOHN DOE"),
            "4111111111111111");
  EXPECT_EQ(LuhnValidator::repair_and_extract("Ref 1234 4111 1111 1111 1111"),
            "4111111111111111");
  // A trailing CVV group must not be glued to the PAN.
  EXPECT_EQ(LuhnValidator::repair_and_extract("4111 1111 1111 1111 123"),
            "4111111111111111");
  // Glued digits without separators (sliding-window fallback).
  EXPECT_EQ(LuhnValidator::repair_and_extract("994111111111111111199"),
            "4111111111111111");
}

TEST(OcrRepairTest, RejectsNonCards) {
  EXPECT_FALSE(LuhnValidator::repair_and_extract(""));
  EXPECT_FALSE(LuhnValidator::repair_and_extract("HELLO WORLD"));
  EXPECT_FALSE(LuhnValidator::repair_and_extract("4111 1111 1111 1112"));
  EXPECT_FALSE(LuhnValidator::repair_and_extract("6011 1111 1111 1117"));
  EXPECT_FALSE(LuhnValidator::repair_and_extract("4111 1111 1111"));
  EXPECT_FALSE(LuhnValidator::repair_and_extract("**** **** **** 1111"));
  // Mostly-letter runs ("SOLO BOSS ...") are not treated as numbers.
  EXPECT_FALSE(LuhnValidator::repair_and_extract("SOLO BOSS SOLO BOSS"));
  // A word in the middle breaks the candidate.
  EXPECT_FALSE(LuhnValidator::repair_and_extract("4111 1111 EXP 1111 1111"));
  // A new line breaks the candidate.
  EXPECT_FALSE(LuhnValidator::repair_and_extract("4111 1111\n1111 1111"));
}

TEST(OcrRepairTest, HandlesBinaryGarbageAndHugeInput) {
  const char garbage[] = {'\x00', '\xff', '4', '\x80', '1', '\x7f'};
  EXPECT_FALSE(LuhnValidator::repair_and_extract(
      std::string_view(garbage, sizeof(garbage))));
  const std::string huge(100000, '1');
  EXPECT_FALSE(LuhnValidator::repair_and_extract(huge));
  std::string long_run;
  for (int i = 0; i < 40; ++i) long_run += "1234 ";
  long_run += "4111 1111 1111 1111";
  EXPECT_EQ(LuhnValidator::repair_and_extract(long_run), "4111111111111111");
}

TEST(CardNumberTest, WipesOnMoveAndExposesLast4) {
  auto card = LuhnValidator::extract_card("5555 5555 5555 4444");
  ASSERT_TRUE(card.has_value());
  EXPECT_EQ(card->brand(), CardBrand::kMastercard);
  EXPECT_EQ(card->last4(), "4444");
  CardNumber moved = std::move(*card);
  EXPECT_TRUE(card->empty());  // NOLINT(bugprone-use-after-move): by design.
  EXPECT_EQ(moved.digits(), "5555555555554444");
  moved.Wipe();
  EXPECT_TRUE(moved.empty());
  EXPECT_EQ(moved.brand(), CardBrand::kUnknown);
}

// ---- SpatialMatcher ------------------------------------------------------------

TEST(SpatialMatcherTest, MatchesTextBelowBarcode) {
  const std::vector<BarcodeItem> barcodes = {
      {"VCH-12345678", {100, 100, 300, 80}}};
  const std::vector<TextItem> texts = {
      {"Gift Voucher", {100, 20, 200, 30}},
      {"VCH 1234 5678", {110, 190, 280, 30}},
      {"12345678", {900, 900, 100, 30}}};  // Overlaps but far away.
  const auto results = match_barcodes_with_text(barcodes, texts);
  ASSERT_EQ(results.size(), 1u);
  EXPECT_EQ(results[0].barcode.payload, "VCH-12345678");
  EXPECT_EQ(results[0].associated_text, "VCH 1234 5678");
}

TEST(SpatialMatcherTest, PrefersVerticalAlignmentOverSideBySide) {
  const std::vector<ItemView> barcodes = {{"9876543210", {100, 100, 200, 60}}};
  const std::vector<ItemView> texts = {
      // Right side: center distance 120 px (closer).
      {"9876543210", {300, 115, 40, 30}},
      // Below: center distance 135 px, but vertically stacked.
      {"9876543210", {100, 250, 200, 30}}};
  const SpatialMatcher matcher;
  SpatialMatcher::Scratch scratch;
  std::vector<MatchPair> pairs;
  matcher.MatchIndices(barcodes, texts, scratch, pairs);
  ASSERT_EQ(pairs.size(), 1u);
  EXPECT_EQ(pairs[0].text_index, 1u);
  EXPECT_EQ(pairs[0].alignment, Alignment::kTextBelow);
}

TEST(SpatialMatcherTest, RequiresPayloadOverlapAndToleratesOcrConfusion) {
  const std::vector<BarcodeItem> barcodes = {{"AB1058", {0, 0, 200, 60}}};
  // No shared characters -> no match even though it is right below.
  EXPECT_TRUE(
      match_barcodes_with_text(barcodes, {{"TOTAL DUE", {0, 70, 200, 20}}})
          .empty());
  // "A8 IO5B" folds to the same canonical string as "AB1058".
  const auto results =
      match_barcodes_with_text(barcodes, {{"A8 IO5B", {0, 70, 200, 20}}});
  ASSERT_EQ(results.size(), 1u);
  EXPECT_EQ(results[0].associated_text, "A8 IO5B");
}

TEST(SpatialMatcherTest, OneToOneAssignmentForMultipleVouchers) {
  const std::vector<BarcodeItem> barcodes = {
      {"111122223333", {0, 0, 200, 60}}, {"444455556666", {0, 200, 200, 60}}};
  const std::vector<TextItem> texts = {
      {"4444 5555 6666", {0, 270, 200, 20}},
      {"1111 2222 3333", {0, 70, 200, 20}}};
  const auto results = match_barcodes_with_text(barcodes, texts);
  ASSERT_EQ(results.size(), 2u);
  EXPECT_EQ(results[0].associated_text, "1111 2222 3333");
  EXPECT_EQ(results[1].associated_text, "4444 5555 6666");
}

TEST(SpatialMatcherTest, IgnoresInvalidBoxesAndEmptyInput) {
  const float nan = std::numeric_limits<float>::quiet_NaN();
  const float inf = std::numeric_limits<float>::infinity();
  EXPECT_TRUE(match_barcodes_with_text({}, {}).empty());
  EXPECT_TRUE(
      match_barcodes_with_text({{"12345", {0, 0, 10, 10}}}, {}).empty());
  EXPECT_TRUE(match_barcodes_with_text({{"12345", {nan, 0, 10, 10}}},
                                       {{"12345", {0, 12, 10, 10}}})
                  .empty());
  EXPECT_TRUE(match_barcodes_with_text({{"12345", {0, 0, inf, 10}}},
                                       {{"12345", {0, 12, 10, 10}}})
                  .empty());
  EXPECT_TRUE(match_barcodes_with_text({{"12345", {0, 0, -10, 10}}},
                                       {{"12345", {0, 12, 10, 10}}})
                  .empty());
  EXPECT_TRUE(match_barcodes_with_text({{"", {0, 0, 10, 10}}},
                                       {{"12345", {0, 12, 10, 10}}})
                  .empty());
}

TEST(SpatialMatcherTest, ScratchReuseAndConcurrentConstUse) {
  const SpatialMatcher matcher;
  const std::vector<ItemView> bv = {{"5012345678900", {0, 0, 200, 60}}};
  const std::vector<ItemView> tv = {{"5 012345 678900", {0, 70, 200, 20}}};
  auto worker = [&]() {
    SpatialMatcher::Scratch scratch;
    std::vector<MatchPair> pairs;
    for (int i = 0; i < 2000; ++i) {
      matcher.MatchIndices(bv, tv, scratch, pairs);
      ASSERT_EQ(pairs.size(), 1u);
    }
  };
  std::thread t1(worker);
  std::thread t2(worker);
  t1.join();
  t2.join();
}

// ---- FrameAnalyzer ---------------------------------------------------------------

TEST(FrameAnalyzerTest, RejectsCorruptedOrMalformedBuffers) {
  const FrameAnalyzer analyzer;
  std::vector<std::uint8_t> pixels(640 * 480, 128);
  EXPECT_EQ(analyzer.Validate({{}, 640, 480, 640}), FrameStatus::kNullBuffer);
  EXPECT_EQ(analyzer.Validate({pixels, 0, 480, 640}),
            FrameStatus::kInvalidDimensions);
  EXPECT_EQ(analyzer.Validate({pixels, -640, 480, 640}),
            FrameStatus::kInvalidDimensions);
  EXPECT_EQ(analyzer.Validate({pixels, 640, 480, 320}),
            FrameStatus::kInvalidStride);
  EXPECT_EQ(analyzer.Validate({pixels, 640, 481, 640}),
            FrameStatus::kBufferTooSmall);
  std::vector<std::uint8_t> wide(5000 * 64, 128);
  EXPECT_EQ(analyzer.Validate({wide, 5000, 64, 5000}),
            FrameStatus::kUnsupportedAspectRatio);
  EXPECT_EQ(analyzer.Validate({pixels, 640, 480, 640}), FrameStatus::kOk);
}

TEST(FrameAnalyzerTest, ExposureAndFocusMetrics) {
  const FrameAnalyzer analyzer;
  std::vector<std::uint8_t> dark(320 * 240, 5);
  EXPECT_EQ(analyzer.Analyze({dark, 320, 240, 320}).status,
            FrameStatus::kTooDark);
  std::vector<std::uint8_t> bright(320 * 240, 252);
  EXPECT_EQ(analyzer.Analyze({bright, 320, 240, 320}).status,
            FrameStatus::kTooBright);
  // High-frequency texture (deterministic LCG noise) -> high focus score;
  // flat gray -> zero focus score.
  std::vector<std::uint8_t> sharp(320 * 240);
  std::uint32_t state = 12345u;
  for (std::uint8_t& px : sharp) {
    state = state * 1664525u + 1013904223u;
    px = static_cast<std::uint8_t>(40 + ((state >> 24) % 176));
  }
  const FrameMetrics sharp_metrics = analyzer.Analyze({sharp, 320, 240, 320});
  EXPECT_EQ(sharp_metrics.status, FrameStatus::kOk);
  EXPECT_GT(sharp_metrics.focus_score, 1000.0f);
  std::vector<std::uint8_t> flat(320 * 240, 128);
  FrameAnalyzerOptions strict;
  strict.min_focus_score = 10.0f;
  EXPECT_EQ(FrameAnalyzer(strict).Analyze({flat, 320, 240, 320}).status,
            FrameStatus::kBlurry);
}

}  // namespace
}  // namespace aegisscan::core
#endif  // AEGISSCAN_FINTECH_CORE_ENABLE_TESTS

#endif  // AEGISSCAN_FINTECH_CORE_HPP_
