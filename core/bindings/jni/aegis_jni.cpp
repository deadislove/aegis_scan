// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Option B (legacy fallback) JNI bindings for com.aegisscan.interop.jni.
//
// Memory rules enforced here:
//   * Text arrives as char[] (never java.lang.String). It's read with
//     GetPrimitiveArrayCritical (pinned, no copy on HotSpot/ART) and ALWAYS
//     released with ReleasePrimitiveArrayCritical via an RAII guard
//     (JNI_ABORT for read-only input, 0 for output).
//   * Image data arrives as a direct ByteBuffer: GetDirectBufferAddress gives
//     the off-heap address with no copy at all. Heap buffers are refused.
//   * No JNI calls or allocations happen inside critical sections.
//   * Nothing is logged. PAN digits are wiped from every native buffer.

#include <jni.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "aegisscan/fintech_core_c.h"

namespace {

// `aegis_extract_expiry`/`aegis_extract_cardholder_name` are UTF-8 only (no
// UTF-16 native entry point), so the char[] input is converted here.
// Surrogate pairs are decoded; 3 bytes per UTF-16 code unit is always enough
// (worst case is a surrogate pair: 2 units -> one 4-byte sequence).
std::size_t Utf16ToUtf8(const uint16_t* src, std::size_t src_len, char* dst,
                        std::size_t dst_cap) {
  std::size_t out = 0;
  for (std::size_t i = 0; i < src_len; ++i) {
    uint32_t cp = src[i];
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < src_len) {
      const uint32_t lo = src[i + 1];
      if (lo >= 0xDC00 && lo <= 0xDFFF) {
        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
        ++i;
      }
    }
    if (cp < 0x80) {
      if (out + 1 > dst_cap) break;
      dst[out++] = static_cast<char>(cp);
    } else if (cp < 0x800) {
      if (out + 2 > dst_cap) break;
      dst[out++] = static_cast<char>(0xC0 | (cp >> 6));
      dst[out++] = static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      if (out + 3 > dst_cap) break;
      dst[out++] = static_cast<char>(0xE0 | (cp >> 12));
      dst[out++] = static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      dst[out++] = static_cast<char>(0x80 | (cp & 0x3F));
    } else {
      if (out + 4 > dst_cap) break;
      dst[out++] = static_cast<char>(0xF0 | (cp >> 18));
      dst[out++] = static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
      dst[out++] = static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      dst[out++] = static_cast<char>(0x80 | (cp & 0x3F));
    }
  }
  return out;
}

// RAII guard: guarantees ReleasePrimitiveArrayCritical on every exit path.
class CriticalArray {
 public:
  CriticalArray(JNIEnv* env, jarray array, jint release_mode)
      : env_(env), array_(array), mode_(release_mode) {
    if (array_ != nullptr) {
      length_ = env_->GetArrayLength(array_);
      data_ = env_->GetPrimitiveArrayCritical(array_, nullptr);
    }
  }
  ~CriticalArray() {
    if (data_ != nullptr) {
      env_->ReleasePrimitiveArrayCritical(array_, data_, mode_);
    }
  }
  CriticalArray(const CriticalArray&) = delete;
  CriticalArray& operator=(const CriticalArray&) = delete;

  void* data() const { return data_; }
  jsize length() const { return length_; }

 private:
  JNIEnv* env_;
  jarray array_;
  jint mode_;
  void* data_ = nullptr;
  jsize length_ = 0;
};

}  // namespace

extern "C" {

// static native int nativeExtractPan(char[] ocr, int len, char[] out, int[] meta)
// meta[0] = digit count, meta[1] = brand. Returns an AEGIS_* status.
JNIEXPORT jint JNICALL
Java_com_aegisscan_interop_jni_FintechCoreJni_nativeExtractPan(
    JNIEnv* env, jclass, jcharArray ocr, jint len, jcharArray out,
    jintArray meta) {
  if (ocr == nullptr || out == nullptr || meta == nullptr || len < 0) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  if (env->GetArrayLength(meta) < 2 ||
      env->GetArrayLength(out) < AEGIS_PAN_BUFFER_SIZE ||
      env->GetArrayLength(ocr) < len) {
    return AEGIS_ERR_BUFFER_TOO_SMALL;
  }
  std::array<uint16_t, AEGIS_PAN_BUFFER_SIZE> digits{};
  int32_t count = 0;
  int32_t brand = 0;
  int32_t status = AEGIS_ERR_INTERNAL;
  {
    CriticalArray in(env, ocr, JNI_ABORT);  // Read-only: no copy-back.
    if (in.data() == nullptr) return AEGIS_ERR_INTERNAL;
    status = aegis_extract_pan_utf16(static_cast<const uint16_t*>(in.data()),
                                     len, digits.data(),
                                     static_cast<int32_t>(digits.size()),
                                     &count, &brand);
  }
  {
    CriticalArray o(env, out, 0);  // Mode 0: commit and release.
    if (o.data() == nullptr) {
      aegis_secure_zero(digits.data(), sizeof(digits));
      return AEGIS_ERR_INTERNAL;
    }
    auto* dst = static_cast<uint16_t*>(o.data());
    for (jsize i = 0; i < o.length(); ++i) {
      dst[i] = i < AEGIS_PAN_BUFFER_SIZE ? digits[static_cast<size_t>(i)] : 0;
    }
  }
  aegis_secure_zero(digits.data(), sizeof(digits));
  const jint meta_values[2] = {count, brand};
  env->SetIntArrayRegion(meta, 0, 2, meta_values);
  return status;
}

// static native int nativeExtractExpiry(char[] ocr, int len, int[] out)
// out[0] = month, out[1] = year. Returns an AEGIS_* status.
JNIEXPORT jint JNICALL
Java_com_aegisscan_interop_jni_FintechCoreJni_nativeExtractExpiry(
    JNIEnv* env, jclass, jcharArray ocr, jint len, jintArray out) {
  if (ocr == nullptr || out == nullptr || len < 0 ||
      env->GetArrayLength(ocr) < len || env->GetArrayLength(out) < 2) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  std::vector<char> utf8(static_cast<std::size_t>(len) * 3 + 1);
  std::size_t utf8_len = 0;
  {
    CriticalArray in(env, ocr, JNI_ABORT);
    if (in.data() == nullptr) return AEGIS_ERR_INTERNAL;
    utf8_len = Utf16ToUtf8(static_cast<const uint16_t*>(in.data()),
                           static_cast<std::size_t>(len), utf8.data(), utf8.size());
  }
  aegis_expiry expiry{};
  const int32_t status = aegis_extract_expiry(
      utf8.data(), static_cast<int32_t>(utf8_len), &expiry);
  const jint values[2] = {expiry.month, expiry.year};
  env->SetIntArrayRegion(out, 0, 2, values);
  return status;
}

// static native int nativeExtractCardholderName(char[] ocr, int len,
//                                                char[] out, int[] outLen)
// out receives UTF-16 characters (the extracted name is pure ASCII by
// construction, so widening each byte is correct); outLen[0] = count.
JNIEXPORT jint JNICALL
Java_com_aegisscan_interop_jni_FintechCoreJni_nativeExtractCardholderName(
    JNIEnv* env, jclass, jcharArray ocr, jint len, jcharArray out,
    jintArray out_len) {
  if (ocr == nullptr || out == nullptr || out_len == nullptr || len < 0 ||
      env->GetArrayLength(ocr) < len || env->GetArrayLength(out_len) < 1) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  std::vector<char> utf8(static_cast<std::size_t>(len) * 3 + 1);
  std::size_t utf8_len = 0;
  {
    CriticalArray in(env, ocr, JNI_ABORT);
    if (in.data() == nullptr) return AEGIS_ERR_INTERNAL;
    utf8_len = Utf16ToUtf8(static_cast<const uint16_t*>(in.data()),
                           static_cast<std::size_t>(len), utf8.data(), utf8.size());
  }
  std::array<char, AEGIS_NAME_BUFFER_SIZE> name{};
  int32_t name_len = 0;
  const int32_t status = aegis_extract_cardholder_name(
      utf8.data(), static_cast<int32_t>(utf8_len), name.data(),
      static_cast<int32_t>(name.size()), &name_len);
  jint written = 0;
  if (status == AEGIS_OK) {
    const jsize out_cap = env->GetArrayLength(out);
    std::array<jchar, AEGIS_NAME_BUFFER_SIZE> wide{};
    while (written < name_len && written < out_cap) {
      wide[static_cast<size_t>(written)] = static_cast<jchar>(
          static_cast<unsigned char>(name[static_cast<size_t>(written)]));
      ++written;
    }
    env->SetCharArrayRegion(out, 0, written, wide.data());
  }
  const jint len_values[1] = {written};
  env->SetIntArrayRegion(out_len, 0, 1, len_values);
  return status;
}

namespace {

// Copies up to `max_len` non-NUL bytes of `field` into `wide`, starting at
// `slot_offset` (widening ASCII bytes to jchar -- every aegis_mrz string
// field is pure A-Z0-9-and-space ASCII by construction). Remaining slot
// positions stay at their default-initialized zero.
void WriteMrzField(const char* field, std::size_t max_len,
                   std::size_t slot_offset, jchar* wide) {
  std::size_t n = 0;
  while (n < max_len && field[n] != '\0') {
    wide[slot_offset + n] =
        static_cast<jchar>(static_cast<unsigned char>(field[n]));
    ++n;
  }
}

}  // namespace

// static native int nativeExtractMrz(char[] ocr, int len, char[] outStrings,
//                                     int[] outFlags)
// outStrings layout (UTF-16, NUL-padded within each fixed slot):
//   [0..9) document_number | [9..12) nationality | [12..18) date_of_birth |
//   [18..24) expiry_date | [24..25) sex | [25..64) surname |
//   [64..103) given_names
// outFlags = {document_number_check_valid, date_of_birth_check_valid,
//             expiry_check_valid, composite_check_valid}
// Returns an AEGIS_* status.
JNIEXPORT jint JNICALL
Java_com_aegisscan_interop_jni_FintechCoreJni_nativeExtractMrz(
    JNIEnv* env, jclass, jcharArray ocr, jint len, jcharArray out_strings,
    jintArray out_flags) {
  if (ocr == nullptr || out_strings == nullptr || out_flags == nullptr ||
      len < 0 || env->GetArrayLength(ocr) < len ||
      env->GetArrayLength(out_strings) < 103 ||
      env->GetArrayLength(out_flags) < 4) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  std::vector<char> utf8(static_cast<std::size_t>(len) * 3 + 1);
  std::size_t utf8_len = 0;
  {
    CriticalArray in(env, ocr, JNI_ABORT);
    if (in.data() == nullptr) return AEGIS_ERR_INTERNAL;
    utf8_len = Utf16ToUtf8(static_cast<const uint16_t*>(in.data()),
                           static_cast<std::size_t>(len), utf8.data(), utf8.size());
  }
  aegis_mrz mrz{};
  const int32_t status = aegis_extract_mrz(
      utf8.data(), static_cast<int32_t>(utf8_len), &mrz);
  if (status == AEGIS_OK) {
    std::array<jchar, 103> wide{};
    WriteMrzField(mrz.document_number, 9, 0, wide.data());
    WriteMrzField(mrz.nationality, 3, 9, wide.data());
    WriteMrzField(mrz.date_of_birth, 6, 12, wide.data());
    WriteMrzField(mrz.expiry_date, 6, 18, wide.data());
    wide[24] = static_cast<jchar>(static_cast<unsigned char>(mrz.sex));
    WriteMrzField(mrz.surname, 39, 25, wide.data());
    WriteMrzField(mrz.given_names, 39, 64, wide.data());
    env->SetCharArrayRegion(out_strings, 0, 103, wide.data());
    const jint flags[4] = {mrz.document_number_check_valid,
                           mrz.date_of_birth_check_valid, mrz.expiry_check_valid,
                           mrz.composite_check_valid};
    env->SetIntArrayRegion(out_flags, 0, 4, flags);
  }
  return status;
}

// static native boolean nativeLuhnValidate(char[] digits, int len)
JNIEXPORT jboolean JNICALL
Java_com_aegisscan_interop_jni_FintechCoreJni_nativeLuhnValidate(
    JNIEnv* env, jclass, jcharArray digits, jint len) {
  if (digits == nullptr || len < 2 || len > 64 ||
      env->GetArrayLength(digits) < len) {
    return JNI_FALSE;
  }
  std::array<char, 64> narrow{};
  {
    CriticalArray in(env, digits, JNI_ABORT);
    if (in.data() == nullptr) return JNI_FALSE;
    const auto* src = static_cast<const uint16_t*>(in.data());
    for (jint i = 0; i < len; ++i) {
      narrow[static_cast<size_t>(i)] =
          src[i] < 0x80 ? static_cast<char>(src[i]) : '~';
    }
  }
  const jboolean ok =
      aegis_luhn_validate(narrow.data(), len) == 1 ? JNI_TRUE : JNI_FALSE;
  aegis_secure_zero(narrow.data(), sizeof(narrow));
  return ok;
}

// static native int nativeAnalyzeLuma(ByteBuffer direct, int w, int h,
//                                     int stride, float[] metricsOut)
// metricsOut = {status, meanLuma, focusScore}. Zero-copy via direct buffer.
JNIEXPORT jint JNICALL
Java_com_aegisscan_interop_jni_FintechCoreJni_nativeAnalyzeLuma(
    JNIEnv* env, jclass, jobject buffer, jint width, jint height, jint stride,
    jfloatArray metrics_out) {
  if (buffer == nullptr || metrics_out == nullptr ||
      env->GetArrayLength(metrics_out) < 3) {
    return AEGIS_ERR_INVALID_ARGUMENT;
  }
  void* address = env->GetDirectBufferAddress(buffer);
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (address == nullptr || capacity < 0) {
    return AEGIS_ERR_INVALID_ARGUMENT;  // Heap buffer: refuse to copy.
  }
  aegis_frame_metrics m{};
  const int32_t status = aegis_analyze_luma(
      static_cast<const uint8_t*>(address), capacity, width, height, stride, &m);
  const jfloat values[3] = {static_cast<jfloat>(m.status), m.mean_luma,
                            m.focus_score};
  env->SetFloatArrayRegion(metrics_out, 0, 3, values);
  return status;
}

JNIEXPORT jlong JNICALL
Java_com_aegisscan_interop_jni_FintechCoreJni_nativeMatcherCreate(
    JNIEnv*, jclass, jfloat frame_width, jfloat frame_height) {
  return reinterpret_cast<jlong>(
      aegis_matcher_create(frame_width, frame_height));
}

JNIEXPORT void JNICALL
Java_com_aegisscan_interop_jni_FintechCoreJni_nativeMatcherDestroy(
    JNIEnv*, jclass, jlong handle) {
  aegis_matcher_destroy(reinterpret_cast<aegis_matcher_t*>(handle));
}

}  // extern "C"
