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
#include <cstdint>

#include "aegisscan/fintech_core_c.h"

namespace {

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
