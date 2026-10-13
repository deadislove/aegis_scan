// Copyright 2026 AegisScan Authors. All rights reserved.
//
// C ABI implementation over the header-only engine. Every entry point is
// noexcept in practice: the only throwing operation (vector growth inside the
// matcher) is caught and mapped to AEGIS_ERR_INTERNAL.

#include "aegisscan/fintech_core_c.h"

#include <algorithm>
#include <array>
#include <new>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

#include "aegisscan/fintech_core.hpp"

namespace core = aegisscan::core;

struct aegis_matcher {
  explicit aegis_matcher(const core::SpatialMatcherOptions& options)
      : matcher(options) {}
  core::SpatialMatcher matcher;
  core::SpatialMatcher::Scratch scratch;
  std::vector<core::ItemView> barcode_views;
  std::vector<core::ItemView> text_views;
  std::vector<core::MatchPair> pairs;
};

namespace {

bool FillViews(const aegis_item* items, int32_t count,
               std::vector<core::ItemView>& out) {
  out.clear();
  if (count < 0 || (count > 0 && items == nullptr)) return false;
  out.reserve(static_cast<std::size_t>(count));
  for (int32_t i = 0; i < count; ++i) {
    const aegis_item& item = items[i];
    std::string_view text;
    if (item.text != nullptr && item.text_len > 0) {
      text = std::string_view(item.text,
                              static_cast<std::size_t>(item.text_len));
    }
    out.push_back(core::ItemView{
        text, core::BoundingBox{item.box.x, item.box.y, item.box.width,
                                item.box.height}});
  }
  return true;
}

// Writes the card to `out` (NUL terminated) and fills the optional outputs.
// The output buffer is always zero-filled first so stale PANs never survive.
template <typename CharT>
int32_t WriteCard(const std::optional<core::CardNumber>& card, CharT* out,
                  int32_t capacity, int32_t* out_len, int32_t* out_brand) {
  core::SecureZero(out, static_cast<std::size_t>(capacity) * sizeof(CharT));
  if (out_len != nullptr) *out_len = 0;
  if (out_brand != nullptr) *out_brand = AEGIS_BRAND_UNKNOWN;
  if (!card) return AEGIS_NOT_FOUND;
  const std::string_view digits = card->digits();
  for (std::size_t i = 0; i < digits.size(); ++i) {
    out[i] = static_cast<CharT>(digits[i]);
  }
  if (out_len != nullptr) *out_len = static_cast<int32_t>(digits.size());
  if (out_brand != nullptr) *out_brand = static_cast<int32_t>(card->brand());
  return AEGIS_OK;
}

template <typename CharT>
bool InvalidExtractArgs(const CharT* raw, int32_t raw_len,
                        const CharT* out) noexcept {
  return out == nullptr || raw_len < 0 || (raw == nullptr && raw_len > 0);
}

}  // namespace

extern "C" {

const char* aegis_version(void) { return core::kVersion.data(); }

int32_t aegis_luhn_validate(const char* digits, int32_t len) {
  if (digits == nullptr || len <= 0) return 0;
  return core::LuhnValidator::validate(
             std::string_view(digits, static_cast<std::size_t>(len)))
             ? 1
             : 0;
}

int32_t aegis_detect_brand(const char* digits, int32_t len) {
  if (digits == nullptr || len <= 0) return AEGIS_BRAND_UNKNOWN;
  return static_cast<int32_t>(core::DetectBrand(
      std::string_view(digits, static_cast<std::size_t>(len))));
}

int32_t aegis_extract_expiry(const char* raw_ocr, int32_t raw_len,
                             aegis_expiry* out_expiry) {
  if (out_expiry == nullptr || raw_len < 0 ||
      (raw_ocr == nullptr && raw_len > 0)) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  out_expiry->month = 0;
  out_expiry->year = 0;
  const std::string_view input =
      raw_len == 0 ? std::string_view()
                   : std::string_view(raw_ocr,
                                      static_cast<std::size_t>(raw_len));
  const std::optional<core::ExpiryDate> expiry = core::extract_expiry(input);
  if (!expiry) return AEGIS_NOT_FOUND;
  out_expiry->month = expiry->month;
  out_expiry->year = expiry->year;
  return AEGIS_OK;
}

int32_t aegis_extract_cardholder_name(const char* raw_ocr, int32_t raw_len,
                                      char* out_name, int32_t out_capacity,
                                      int32_t* out_len) {
  if (InvalidExtractArgs(raw_ocr, raw_len, out_name)) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  if (out_len != nullptr) *out_len = 0;
  const std::string_view input =
      raw_len == 0 ? std::string_view()
                   : std::string_view(raw_ocr,
                                      static_cast<std::size_t>(raw_len));
  const std::optional<std::string> name = core::extract_cardholder_name(input);
  if (!name) return AEGIS_NOT_FOUND;
  const std::size_t n =
      std::min(name->size(), static_cast<std::size_t>(out_capacity));
  std::copy_n(name->data(), n, out_name);
  if (out_len != nullptr) *out_len = static_cast<int32_t>(n);
  return AEGIS_OK;
}

namespace {

// Copies `in` into `out` (capacity `cap`, including the NUL), truncating
// rather than rejecting, then NUL-terminates. Matches the existing
// cardholder-name truncation contract.
void CopyTruncated(std::string_view in, char* out, std::size_t cap) {
  const std::size_t n = std::min(in.size(), cap - 1);
  std::copy_n(in.data(), n, out);
  out[n] = '\0';
}

}  // namespace

int32_t aegis_extract_mrz(const char* raw_ocr, int32_t raw_len,
                          aegis_mrz* out_mrz) {
  if (out_mrz == nullptr || raw_len < 0 ||
      (raw_ocr == nullptr && raw_len > 0)) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  *out_mrz = aegis_mrz{};
  out_mrz->sex = '<';
  const std::string_view input =
      raw_len == 0 ? std::string_view()
                   : std::string_view(raw_ocr,
                                      static_cast<std::size_t>(raw_len));
  const std::optional<core::MrzResult> mrz = core::extract_mrz(input);
  if (!mrz) return AEGIS_NOT_FOUND;
  CopyTruncated(mrz->document_number, out_mrz->document_number,
               sizeof(out_mrz->document_number));
  CopyTruncated(mrz->nationality, out_mrz->nationality,
               sizeof(out_mrz->nationality));
  CopyTruncated(mrz->date_of_birth, out_mrz->date_of_birth,
               sizeof(out_mrz->date_of_birth));
  CopyTruncated(mrz->expiry_date, out_mrz->expiry_date,
               sizeof(out_mrz->expiry_date));
  out_mrz->sex = mrz->sex;
  CopyTruncated(mrz->surname, out_mrz->surname, sizeof(out_mrz->surname));
  CopyTruncated(mrz->given_names, out_mrz->given_names,
               sizeof(out_mrz->given_names));
  out_mrz->document_number_check_valid = mrz->document_number_check_valid ? 1 : 0;
  out_mrz->date_of_birth_check_valid = mrz->date_of_birth_check_valid ? 1 : 0;
  out_mrz->expiry_check_valid = mrz->expiry_check_valid ? 1 : 0;
  out_mrz->composite_check_valid = mrz->composite_check_valid ? 1 : 0;
  return AEGIS_OK;
}

int32_t aegis_extract_pan(const char* raw_ocr, int32_t raw_len,
                          char* out_digits, int32_t out_capacity,
                          int32_t* out_len, int32_t* out_brand) {
  if (InvalidExtractArgs(raw_ocr, raw_len, out_digits)) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  if (out_capacity < AEGIS_PAN_BUFFER_SIZE) return AEGIS_ERR_BUFFER_TOO_SMALL;
  const std::string_view input =
      raw_len == 0 ? std::string_view()
                   : std::string_view(raw_ocr,
                                      static_cast<std::size_t>(raw_len));
  return WriteCard(core::LuhnValidator::extract_card(input), out_digits,
                   out_capacity, out_len, out_brand);
}

int32_t aegis_extract_pan_utf16(const uint16_t* raw_ocr, int32_t raw_len,
                                uint16_t* out_digits, int32_t out_capacity,
                                int32_t* out_len, int32_t* out_brand) {
  if (InvalidExtractArgs(raw_ocr, raw_len, out_digits)) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  if (out_capacity < AEGIS_PAN_BUFFER_SIZE) return AEGIS_ERR_BUFFER_TOO_SMALL;
  static_assert(sizeof(char16_t) == sizeof(uint16_t));
  const std::u16string_view input =
      raw_len == 0
          ? std::u16string_view()
          : std::u16string_view(reinterpret_cast<const char16_t*>(raw_ocr),
                                static_cast<std::size_t>(raw_len));
  return WriteCard(core::LuhnValidator::extract_card(input), out_digits,
                   out_capacity, out_len, out_brand);
}

int32_t aegis_analyze_luma(const uint8_t* data, int64_t data_len,
                           int32_t width, int32_t height, int32_t row_stride,
                           aegis_frame_metrics* out_metrics) {
  if (out_metrics == nullptr || data_len < 0) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  core::LumaFrame frame;
  if (data != nullptr && data_len > 0) {
    frame.data =
        std::span<const uint8_t>(data, static_cast<std::size_t>(data_len));
  }
  frame.width = width;
  frame.height = height;
  frame.row_stride = row_stride;
  const core::FrameMetrics metrics = core::FrameAnalyzer().Analyze(frame);
  out_metrics->status = static_cast<int32_t>(metrics.status);
  out_metrics->mean_luma = metrics.mean_luma;
  out_metrics->focus_score = metrics.focus_score;
  return AEGIS_OK;
}

aegis_matcher_t* aegis_matcher_create(float frame_width, float frame_height) {
  core::SpatialMatcherOptions options;
  options.frame_width = frame_width;
  options.frame_height = frame_height;
  return new (std::nothrow) aegis_matcher(options);
}

void aegis_matcher_destroy(aegis_matcher_t* matcher) { delete matcher; }

int32_t aegis_matcher_match(aegis_matcher_t* matcher,
                            const aegis_item* barcodes, int32_t barcode_count,
                            const aegis_item* texts, int32_t text_count,
                            aegis_match* out, int32_t out_capacity,
                            int32_t* out_count) {
  if (out_count != nullptr) *out_count = 0;
  if (matcher == nullptr || out_capacity < 0 ||
      (out_capacity > 0 && out == nullptr)) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  try {
    if (!FillViews(barcodes, barcode_count, matcher->barcode_views) ||
        !FillViews(texts, text_count, matcher->text_views)) {
      matcher->barcode_views.clear();
      matcher->text_views.clear();
      return AEGIS_ERR_INVALID_ARGUMENT;
    }
    matcher->matcher.MatchIndices(matcher->barcode_views, matcher->text_views,
                                  matcher->scratch, matcher->pairs);
  } catch (...) {
    matcher->barcode_views.clear();
    matcher->text_views.clear();
    return AEGIS_ERR_INTERNAL;  // Only std::bad_alloc is possible.
  }
  // Views reference caller memory: drop them so nothing dangles.
  matcher->barcode_views.clear();
  matcher->text_views.clear();

  const std::size_t n =
      std::min(matcher->pairs.size(), static_cast<std::size_t>(out_capacity));
  for (std::size_t i = 0; i < n; ++i) {
    const core::MatchPair& p = matcher->pairs[i];
    out[i] = aegis_match{static_cast<int32_t>(p.barcode_index),
                         static_cast<int32_t>(p.text_index),
                         p.score,
                         p.normalized_distance,
                         static_cast<int32_t>(p.overlap_chars),
                         static_cast<int32_t>(p.alignment)};
  }
  if (out_count != nullptr) *out_count = static_cast<int32_t>(n);
  return matcher->pairs.size() > n ? AEGIS_ERR_BUFFER_TOO_SMALL : AEGIS_OK;
}

void aegis_secure_zero(void* data, int64_t len) {
  if (data != nullptr && len > 0) {
    core::SecureZero(data, static_cast<std::size_t>(len));
  }
}

}  // extern "C"
