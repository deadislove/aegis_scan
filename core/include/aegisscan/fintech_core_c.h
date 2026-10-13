/*
 * Copyright 2026 AegisScan Authors. All rights reserved.
 *
 * fintech_core_c.h -- Stable C ABI over fintech_core.hpp.
 *
 * Consumed by Swift (module map), C# (.NET 8 [LibraryImport]), Java 21+
 * (FFM / Panama) and the JNI fallback. Rules:
 *   - No C++ exception ever crosses this boundary (all functions noexcept).
 *   - The library never allocates memory that the caller must free, except
 *     through the explicit aegis_matcher_create / aegis_matcher_destroy pair.
 *   - PAN digits are written into caller-owned buffers only; the library wipes
 *     every internal copy before returning. Callers MUST wipe their buffer.
 *   - Nothing is logged, nothing touches the file system.
 *   - All functions are thread-safe; an aegis_matcher_t handle must not be
 *     used concurrently by two threads (it owns per-instance scratch memory).
 */
#ifndef AEGISSCAN_FINTECH_CORE_C_H_
#define AEGISSCAN_FINTECH_CORE_C_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(AEGIS_CORE_BUILD)
#define AEGIS_API __declspec(dllexport)
#else
#define AEGIS_API __declspec(dllimport)
#endif
#else
#define AEGIS_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Status codes (stable values, mirrored by every binding). */
#define AEGIS_OK 0
#define AEGIS_NOT_FOUND 1
#define AEGIS_ERR_INVALID_ARGUMENT -1
#define AEGIS_ERR_BUFFER_TOO_SMALL -2
#define AEGIS_ERR_INTERNAL -3

/* Card brands (mirror aegisscan::core::CardBrand). */
#define AEGIS_BRAND_UNKNOWN 0
#define AEGIS_BRAND_VISA 1
#define AEGIS_BRAND_MASTERCARD 2
#define AEGIS_BRAND_AMEX 3
#define AEGIS_BRAND_JCB 4

/* Minimum capacity for PAN output buffers (19 digits + NUL). */
#define AEGIS_PAN_BUFFER_SIZE 20

/* Recommended capacity for cardholder name output buffers. Longer names are
 * truncated rather than rejected. */
#define AEGIS_NAME_BUFFER_SIZE 64

typedef struct aegis_box {
  float x;
  float y;
  float width;
  float height;
} aegis_box;

/* Borrowed UTF-8 string + box. `text` need not be NUL-terminated. */
typedef struct aegis_item {
  const char* text;
  int32_t text_len;
  aegis_box box;
} aegis_item;

typedef struct aegis_match {
  int32_t barcode_index;
  int32_t text_index;
  float score;
  float normalized_distance;
  int32_t overlap_chars;
  int32_t alignment; /* aegisscan::core::Alignment */
} aegis_match;

typedef struct aegis_frame_metrics {
  int32_t status; /* aegisscan::core::FrameStatus */
  float mean_luma;
  float focus_score;
} aegis_frame_metrics;

typedef struct aegis_matcher aegis_matcher_t;

/* Library version string (static storage, never freed). */
AEGIS_API const char* aegis_version(void);

/* 1 when `digits[0..len)` passes Luhn Mod-10, else 0. */
AEGIS_API int32_t aegis_luhn_validate(const char* digits, int32_t len);

/*
 * Repairs OCR text and extracts the first valid PAN.
 *   out_digits : caller buffer, capacity >= AEGIS_PAN_BUFFER_SIZE. On success
 *                receives the NUL-terminated digits; otherwise zero-filled.
 *   out_len    : digits written (0 when not found). May be NULL.
 *   out_brand  : AEGIS_BRAND_* (may be NULL).
 * Returns AEGIS_OK, AEGIS_NOT_FOUND or a negative error.
 */
AEGIS_API int32_t aegis_extract_pan(const char* raw_ocr, int32_t raw_len,
                                    char* out_digits, int32_t out_capacity,
                                    int32_t* out_len, int32_t* out_brand);

/* Same as aegis_extract_pan for UTF-16 input (C#/Java strings, 0-copy). Code
 * units > 0x7F are treated as separator-free non-digits. */
AEGIS_API int32_t aegis_extract_pan_utf16(const uint16_t* raw_ocr,
                                          int32_t raw_len, uint16_t* out_digits,
                                          int32_t out_capacity,
                                          int32_t* out_len, int32_t* out_brand);

/* Brand of an all-digit PAN (AEGIS_BRAND_*). */
AEGIS_API int32_t aegis_detect_brand(const char* digits, int32_t len);

typedef struct aegis_expiry {
  int32_t month; /* 1-12 */
  int32_t year;  /* 4-digit, e.g. 2029 */
} aegis_expiry;

/*
 * Scans for the first MM/YY or MM/YYYY pattern in raw OCR text. Unlike PAN
 * extraction, there is no checksum to validate an expiry date against -- see
 * extract_expiry()'s doc comment in fintech_core.hpp for the accuracy
 * caveat. Not a hot-path call (expected to run once per confirmed card, not
 * once per frame), so UTF-8 only -- no zero-copy UTF-16 overload.
 * Returns AEGIS_OK with *out_expiry filled, AEGIS_NOT_FOUND, or a negative
 * error.
 */
AEGIS_API int32_t aegis_extract_expiry(const char* raw_ocr, int32_t raw_len,
                                       aegis_expiry* out_expiry);

/*
 * Best-effort cardholder name extraction (see fintech_core.hpp for the
 * heuristic and its accuracy caveat relative to PAN/expiry extraction).
 * Also UTF-8 only, for the same reason as aegis_extract_expiry.
 *   out_name : caller buffer. On success receives UTF-8 bytes, truncated to
 *              out_capacity if the matched line doesn't fit; not
 *              NUL-terminated beyond out_len.
 *   out_len  : bytes written (0 when not found). May be NULL.
 * Returns AEGIS_OK, AEGIS_NOT_FOUND, or a negative error.
 */
AEGIS_API int32_t aegis_extract_cardholder_name(const char* raw_ocr,
                                                int32_t raw_len,
                                                char* out_name,
                                                int32_t out_capacity,
                                                int32_t* out_len);

/* Fixed buffer sizes for aegis_mrz string fields, each including the NUL
 * terminator. Longer document numbers/names are truncated, not rejected. */
#define AEGIS_MRZ_DOC_NUMBER_SIZE 10 /* 9 chars + NUL */
#define AEGIS_MRZ_NATIONALITY_SIZE 4 /* 3 chars + NUL */
#define AEGIS_MRZ_DATE_SIZE 7        /* YYMMDD + NUL */
#define AEGIS_MRZ_NAME_FIELD_SIZE 40 /* surname or given names, truncated */

/*
 * Mirrors aegisscan::core::MrzResult. Every *_check_valid field is an ICAO
 * 9303 check digit (same mental model as Luhn backing a PAN) -- unlike
 * aegis_expiry/cardholder name above, these are not purely best-effort.
 * composite_check_valid covers document number, date of birth, and expiry
 * (and, for a TD3 passport, the personal-number field) all at once.
 */
typedef struct aegis_mrz {
  char document_number[AEGIS_MRZ_DOC_NUMBER_SIZE];
  char nationality[AEGIS_MRZ_NATIONALITY_SIZE];
  char date_of_birth[AEGIS_MRZ_DATE_SIZE]; /* YYMMDD */
  char expiry_date[AEGIS_MRZ_DATE_SIZE];   /* YYMMDD */
  char sex;                                /* 'M', 'F', or '<' (unspecified) */
  char surname[AEGIS_MRZ_NAME_FIELD_SIZE];
  char given_names[AEGIS_MRZ_NAME_FIELD_SIZE];
  int32_t document_number_check_valid; /* 0 or 1 */
  int32_t date_of_birth_check_valid;   /* 0 or 1 */
  int32_t expiry_check_valid;          /* 0 or 1 */
  int32_t composite_check_valid;       /* 0 or 1 */
} aegis_mrz;

/*
 * Scans raw OCR text for a TD3 (passport, 2 lines x 44 chars) or TD1 (ID
 * card, 3 lines x 30 chars) MRZ block and parses it. UTF-8 only, like
 * aegis_extract_expiry/aegis_extract_cardholder_name -- the MRZ alphabet
 * (A-Z, 0-9, <) is itself a 7-bit ASCII subset, so this is never a real
 * limitation. Returns AEGIS_OK with *out_mrz filled whenever an MRZ-shaped
 * line run is found (check the *_check_valid fields to see which parsed
 * fields to trust), AEGIS_NOT_FOUND when no such run exists at all, or a
 * negative error.
 */
AEGIS_API int32_t aegis_extract_mrz(const char* raw_ocr, int32_t raw_len,
                                    aegis_mrz* out_mrz);

/* Validates and measures an 8-bit luma plane (zero-copy, read-only). */
AEGIS_API int32_t aegis_analyze_luma(const uint8_t* data, int64_t data_len,
                                     int32_t width, int32_t height,
                                     int32_t row_stride,
                                     aegis_frame_metrics* out_metrics);

/* Spatial matcher with per-handle scratch (frame_w/h <= 0: auto frame). */
AEGIS_API aegis_matcher_t* aegis_matcher_create(float frame_width,
                                                float frame_height);
AEGIS_API void aegis_matcher_destroy(aegis_matcher_t* matcher);

/*
 * One-to-one barcode/text association. Writes up to `out_capacity` matches,
 * sorted by barcode index. `out_count` receives the number written.
 */
AEGIS_API int32_t aegis_matcher_match(aegis_matcher_t* matcher,
                                      const aegis_item* barcodes,
                                      int32_t barcode_count,
                                      const aegis_item* texts,
                                      int32_t text_count, aegis_match* out,
                                      int32_t out_capacity, int32_t* out_count);

/* Volatile wipe helper for binding-side buffers. */
AEGIS_API void aegis_secure_zero(void* data, int64_t len);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* AEGISSCAN_FINTECH_CORE_C_H_ */
