// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Tests for the stable C ABI (what Swift / C# / Java actually call).

#include <gtest/gtest.h>

#include <array>
#include <atomic>
#include <string>
#include <thread>
#include <vector>

#include "aegisscan/fintech_core_c.h"

namespace {

aegis_item Item(const std::string& s, float x, float y, float w, float h) {
  return aegis_item{s.data(), static_cast<int32_t>(s.size()), {x, y, w, h}};
}

TEST(CAbiTest, VersionAndLuhn) {
  EXPECT_STREQ(aegis_version(), "1.0.0");
  EXPECT_EQ(aegis_luhn_validate("4111111111111111", 16), 1);
  EXPECT_EQ(aegis_luhn_validate("4111111111111112", 16), 0);
  EXPECT_EQ(aegis_luhn_validate(nullptr, 16), 0);
  EXPECT_EQ(aegis_luhn_validate("4111", -1), 0);
  EXPECT_EQ(aegis_detect_brand("378282246310005", 15), AEGIS_BRAND_AMEX);
  EXPECT_EQ(aegis_detect_brand(nullptr, 0), AEGIS_BRAND_UNKNOWN);
}

TEST(CAbiTest, ExtractPanUtf8) {
  const std::string ocr = "CARD 4O12 B888 88B8 1881 EXP 12/28";
  std::array<char, AEGIS_PAN_BUFFER_SIZE> out{};
  int32_t len = -1;
  int32_t brand = -1;
  ASSERT_EQ(aegis_extract_pan(ocr.data(), static_cast<int32_t>(ocr.size()),
                              out.data(), static_cast<int32_t>(out.size()),
                              &len, &brand),
            AEGIS_OK);
  EXPECT_EQ(len, 16);
  EXPECT_EQ(brand, AEGIS_BRAND_VISA);
  EXPECT_STREQ(out.data(), "4012888888881881");
  aegis_secure_zero(out.data(), static_cast<int64_t>(out.size()));
  for (char c : out) EXPECT_EQ(c, '\0');
}

TEST(CAbiTest, ExtractPanUtf16) {
  const std::u16string ocr = u"N\u00ba 5555 SSSS 5555 4444 \u20ac";
  std::array<uint16_t, AEGIS_PAN_BUFFER_SIZE> out{};
  int32_t len = 0;
  int32_t brand = 0;
  ASSERT_EQ(aegis_extract_pan_utf16(
                reinterpret_cast<const uint16_t*>(ocr.data()),
                static_cast<int32_t>(ocr.size()), out.data(),
                static_cast<int32_t>(out.size()), &len, &brand),
            AEGIS_OK);
  EXPECT_EQ(len, 16);
  EXPECT_EQ(brand, AEGIS_BRAND_MASTERCARD);
  const std::u16string got(reinterpret_cast<const char16_t*>(out.data()),
                           static_cast<std::size_t>(len));
  EXPECT_EQ(got, u"5555555555554444");
}

TEST(CAbiTest, ExtractPanErrorsAndNotFoundWipeOutput) {
  std::array<char, AEGIS_PAN_BUFFER_SIZE> out{};
  out.fill('X');
  int32_t len = 99;
  EXPECT_EQ(aegis_extract_pan("HELLO", 5, out.data(),
                              static_cast<int32_t>(out.size()), &len, nullptr),
            AEGIS_NOT_FOUND);
  EXPECT_EQ(len, 0);
  for (char c : out) EXPECT_EQ(c, '\0');  // Stale data wiped.
  EXPECT_EQ(aegis_extract_pan("4111", 4, out.data(), 5, nullptr, nullptr),
            AEGIS_ERR_BUFFER_TOO_SMALL);
  EXPECT_EQ(aegis_extract_pan(nullptr, 4, out.data(), 20, nullptr, nullptr),
            AEGIS_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(aegis_extract_pan("4111", 4, nullptr, 20, nullptr, nullptr),
            AEGIS_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(aegis_extract_pan(nullptr, 0, out.data(), 20, nullptr, nullptr),
            AEGIS_NOT_FOUND);
}

TEST(CAbiTest, AnalyzeLumaDefendsAgainstCorruptBuffers) {
  std::vector<uint8_t> px(640 * 480, 128);
  aegis_frame_metrics m{};
  EXPECT_EQ(aegis_analyze_luma(px.data(), static_cast<int64_t>(px.size()), 640,
                               480, 640, &m),
            AEGIS_OK);
  EXPECT_EQ(m.status, 0);
  EXPECT_NEAR(m.mean_luma, 128.0f, 0.01f);
  EXPECT_EQ(aegis_analyze_luma(px.data(), 100, 640, 480, 640, &m), AEGIS_OK);
  EXPECT_EQ(m.status, 4);  // kBufferTooSmall
  EXPECT_EQ(aegis_analyze_luma(nullptr, 0, 640, 480, 640, &m), AEGIS_OK);
  EXPECT_EQ(m.status, 1);  // kNullBuffer
  EXPECT_EQ(aegis_analyze_luma(px.data(), -1, 640, 480, 640, &m),
            AEGIS_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(aegis_analyze_luma(px.data(), 10, 640, 480, 640, nullptr),
            AEGIS_ERR_INVALID_ARGUMENT);
}

TEST(CAbiTest, MatcherLifecycleAndMatching) {
  aegis_matcher_t* matcher = aegis_matcher_create(1920.0f, 1080.0f);
  ASSERT_NE(matcher, nullptr);
  const std::string p1 = "VCH-12345678";
  const std::string t1 = "Gift Voucher";
  const std::string t2 = "VCH 1234 5678";
  const std::array<aegis_item, 1> barcodes = {Item(p1, 100, 100, 300, 80)};
  const std::array<aegis_item, 2> texts = {Item(t1, 100, 20, 200, 30),
                                           Item(t2, 110, 190, 280, 30)};
  std::array<aegis_match, 4> out{};
  int32_t count = -1;
  ASSERT_EQ(aegis_matcher_match(matcher, barcodes.data(), 1, texts.data(), 2,
                                out.data(), 4, &count),
            AEGIS_OK);
  ASSERT_EQ(count, 1);
  EXPECT_EQ(out[0].barcode_index, 0);
  EXPECT_EQ(out[0].text_index, 1);
  EXPECT_EQ(out[0].alignment, 0);  // kTextBelow

  // Capacity too small -> partial result + error code.
  EXPECT_EQ(aegis_matcher_match(matcher, barcodes.data(), 1, texts.data(), 2,
                                out.data(), 0, &count),
            AEGIS_ERR_BUFFER_TOO_SMALL);
  EXPECT_EQ(count, 0);
  // Invalid arguments.
  EXPECT_EQ(aegis_matcher_match(nullptr, barcodes.data(), 1, texts.data(), 2,
                                out.data(), 4, &count),
            AEGIS_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(aegis_matcher_match(matcher, nullptr, 1, texts.data(), 2,
                                out.data(), 4, &count),
            AEGIS_ERR_INVALID_ARGUMENT);
  EXPECT_EQ(aegis_matcher_match(matcher, barcodes.data(), -1, texts.data(), 2,
                                out.data(), 4, &count),
            AEGIS_ERR_INVALID_ARGUMENT);
  // Empty input is fine.
  EXPECT_EQ(aegis_matcher_match(matcher, nullptr, 0, nullptr, 0, out.data(), 4,
                                &count),
            AEGIS_OK);
  EXPECT_EQ(count, 0);
  aegis_matcher_destroy(matcher);
  aegis_matcher_destroy(nullptr);  // No-op.
}

// One matcher per thread + shared stateless functions: must be TSan clean.
TEST(CAbiConcurrencyTest, ParallelExtractionAndMatching) {
  constexpr int kThreads = 8;
  constexpr int kIterations = 500;
  std::atomic<int> successes{0};
  std::vector<std::thread> threads;
  threads.reserve(kThreads);
  for (int t = 0; t < kThreads; ++t) {
    threads.emplace_back([&successes]() {
      aegis_matcher_t* matcher = aegis_matcher_create(0, 0);
      const std::string payload = "5012345678900";
      const std::string text = "5 012345 678900";
      const aegis_item b = Item(payload, 0, 0, 200, 60);
      const aegis_item x = Item(text, 0, 70, 200, 20);
      const std::string ocr = "4111 1111 1111 1111";
      for (int i = 0; i < kIterations; ++i) {
        std::array<char, AEGIS_PAN_BUFFER_SIZE> pan{};
        int32_t len = 0;
        aegis_match m{};
        int32_t count = 0;
        const bool ok =
            aegis_extract_pan(ocr.data(), static_cast<int32_t>(ocr.size()),
                              pan.data(), AEGIS_PAN_BUFFER_SIZE, &len,
                              nullptr) == AEGIS_OK &&
            aegis_matcher_match(matcher, &b, 1, &x, 1, &m, 1, &count) ==
                AEGIS_OK &&
            count == 1;
        aegis_secure_zero(pan.data(), AEGIS_PAN_BUFFER_SIZE);
        if (ok) successes.fetch_add(1, std::memory_order_relaxed);
      }
      aegis_matcher_destroy(matcher);
    });
  }
  for (std::thread& th : threads) th.join();
  EXPECT_EQ(successes.load(), kThreads * kIterations);
}

}  // namespace
