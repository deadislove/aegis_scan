// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop.jni;

import com.aegisscan.interop.ExpiryDate;
import com.aegisscan.interop.FintechCoreException;
import com.aegisscan.interop.FrameMetrics;
import com.aegisscan.interop.MrzResult;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Option B: legacy JNI fallback (Android / JDK &lt; 21 without FFM).
 *
 * <p>Native side: {@code core/bindings/jni/aegis_jni.cpp}. Text goes in as
 * {@code char[]} (pinned with GetPrimitiveArrayCritical, so no copy) and is
 * always released with ReleasePrimitiveArrayCritical through an RAII guard.
 * Images go in as direct ByteBuffers (GetDirectBufferAddress, zero copy).
 *
 * <p>The instance owns a native matcher handle. Use try-with-resources so it's
 * freed deterministically. Nothing is logged.
 */
public final class FintechCoreJni implements AutoCloseable {
  public static final int OK = 0;
  public static final int NOT_FOUND = 1;
  public static final int PAN_BUFFER_SIZE = 20;
  public static final int NAME_BUFFER_SIZE = 64;

  private static volatile boolean loaded;

  /** Loads {@code libaegis_jni} from java.library.path. */
  public static synchronized void loadLibrary() {
    if (!loaded) {
      System.loadLibrary("aegis_jni");
      loaded = true;
    }
  }

  /** Loads the library from an absolute path (servers / tests). */
  public static synchronized void load(String absolutePath) {
    if (!loaded) {
      System.load(absolutePath);
      loaded = true;
    }
  }

  private long handle;

  public FintechCoreJni(float frameWidth, float frameHeight) {
    if (!loaded) loadLibrary();
    handle = nativeMatcherCreate(frameWidth, frameHeight);
    if (handle == 0) throw new FintechCoreException(-3, "nativeMatcherCreate");
  }

  /** Result of a PAN extraction. {@link #close()} wipes the digits. */
  public static final class Pan implements AutoCloseable {
    private final char[] digits;
    public final int brand;
    public final ExpiryDate expiry;
    public final String cardholderName;

    Pan(char[] digits, int brand) {
      this(digits, brand, null, null);
    }

    Pan(char[] digits, int brand, ExpiryDate expiry, String cardholderName) {
      this.digits = digits;
      this.brand = brand;
      this.expiry = expiry;
      this.cardholderName = cardholderName;
    }

    public int length() { return digits.length; }

    public char digitAt(int i) { return digits[i]; }

    public String last4() {
      return digits.length >= 4 ? new String(digits, digits.length - 4, 4) : "";
    }

    @Override public void close() { Arrays.fill(digits, '\0'); }

    @Override public String toString() { return "Pan(brand=" + brand + ", ****" + last4() + ")"; }
  }

  /** Extracts a PAN. All native and temporary buffers are wiped. */
  public Pan extractPan(char[] rawOcr) {
    char[] out = new char[PAN_BUFFER_SIZE];
    int[] meta = new int[2];
    try {
      int status = nativeExtractPan(rawOcr, rawOcr.length, out, meta);
      if (status == NOT_FOUND) return null;
      if (status != OK) throw new FintechCoreException(status, "nativeExtractPan");
      return new Pan(Arrays.copyOf(out, meta[0]), meta[1]);
    } finally {
      Arrays.fill(out, '\0');
    }
  }

  /**
   * Scans for the first MM/YY or MM/YYYY pattern in raw OCR text. Unlike
   * {@link #extractPan}, there's no checksum to validate an expiry date
   * against -- the month range check (01-12) is the only guard against an
   * obviously-wrong match.
   */
  public ExpiryDate extractExpiry(char[] rawOcr) {
    int[] out = new int[2];
    int status = nativeExtractExpiry(rawOcr, rawOcr.length, out);
    if (status == NOT_FOUND) return null;
    if (status != OK) throw new FintechCoreException(status, "nativeExtractExpiry");
    return new ExpiryDate(out[0], out[1]);
  }

  /**
   * Best-effort cardholder name: the longest line in the raw OCR text that
   * looks like a printed name (all uppercase letters/spaces, 2+ words, no
   * known bank/network word). Markedly less reliable than {@link #extractPan}
   * or {@link #extractExpiry} -- there's no structural signal to validate
   * against. A name alone isn't PCI-DSS cardholder data, so this returns a
   * plain {@link String}.
   */
  public String extractCardholderName(char[] rawOcr) {
    char[] out = new char[NAME_BUFFER_SIZE];
    int[] outLen = new int[1];
    int status = nativeExtractCardholderName(rawOcr, rawOcr.length, out, outLen);
    if (status == NOT_FOUND) return null;
    if (status != OK) throw new FintechCoreException(status, "nativeExtractCardholderName");
    return new String(out, 0, outLen[0]);
  }

  private static final int MRZ_DOC_NUMBER_WIDTH = 9;
  private static final int MRZ_NATIONALITY_WIDTH = 3;
  private static final int MRZ_DATE_WIDTH = 6;
  private static final int MRZ_NAME_WIDTH = 39;
  private static final int MRZ_STRINGS_SIZE = MRZ_DOC_NUMBER_WIDTH + MRZ_NATIONALITY_WIDTH
      + MRZ_DATE_WIDTH * 2 + 1 + MRZ_NAME_WIDTH * 2; // 103; +1 is the 1-char sex slot.

  /**
   * Scans for a TD3 (passport, 2 lines x 44 chars) or TD1 (ID card, 3 lines
   * x 30 chars) MRZ block in raw OCR text. Returns null only when no
   * MRZ-shaped line run is found at all; when one is found but its check
   * digits fail, the fields are still returned with their *CheckValid
   * flags false.
   */
  public MrzResult extractMrz(char[] rawOcr) {
    char[] strings = new char[MRZ_STRINGS_SIZE];
    int[] flags = new int[4];
    int status = nativeExtractMrz(rawOcr, rawOcr.length, strings, flags);
    if (status == NOT_FOUND) return null;
    if (status != OK) throw new FintechCoreException(status, "nativeExtractMrz");
    int off = 0;
    String documentNumber = mrzField(strings, off, MRZ_DOC_NUMBER_WIDTH);
    off += MRZ_DOC_NUMBER_WIDTH;
    String nationality = mrzField(strings, off, MRZ_NATIONALITY_WIDTH);
    off += MRZ_NATIONALITY_WIDTH;
    String dateOfBirth = mrzField(strings, off, MRZ_DATE_WIDTH);
    off += MRZ_DATE_WIDTH;
    String expiryDate = mrzField(strings, off, MRZ_DATE_WIDTH);
    off += MRZ_DATE_WIDTH;
    char sex = strings[off];
    off += 1;
    String surname = mrzField(strings, off, MRZ_NAME_WIDTH);
    off += MRZ_NAME_WIDTH;
    String givenNames = mrzField(strings, off, MRZ_NAME_WIDTH);
    return new MrzResult(documentNumber, nationality, dateOfBirth, expiryDate, sex, surname,
        givenNames, flags[0] != 0, flags[1] != 0, flags[2] != 0, flags[3] != 0);
  }

  private static String mrzField(char[] buf, int offset, int maxLen) {
    int n = 0;
    while (n < maxLen && buf[offset + n] != '\0') n++;
    return new String(buf, offset, n);
  }

  /**
   * PAN + expiry + cardholder name from one OCR pass. {@link #extractPan}
   * stays PAN-only for callers that don't want the extra scans.
   */
  public Pan extractCardDetails(char[] rawOcr) {
    Pan pan = extractPan(rawOcr);
    if (pan == null) return null;
    ExpiryDate expiry = extractExpiry(rawOcr);
    String name = extractCardholderName(rawOcr);
    if (expiry == null && name == null) return pan;
    char[] digits = new char[pan.length()];
    for (int i = 0; i < digits.length; i++) digits[i] = pan.digitAt(i);
    Pan withDetails = new Pan(digits, pan.brand, expiry, name);
    pan.close();
    return withDetails;
  }

  public boolean validateLuhn(char[] digits) {
    return nativeLuhnValidate(digits, digits.length);
  }

  /** Zero-copy luma analysis. {@code luma} MUST be a direct ByteBuffer. */
  public FrameMetrics analyzeLuma(ByteBuffer luma, int width, int height, int stride) {
    if (!luma.isDirect()) throw new IllegalArgumentException("ByteBuffer must be direct (zero-copy)");
    float[] m = new float[3];
    int status = nativeAnalyzeLuma(luma, width, height, stride, m);
    if (status != OK) throw new FintechCoreException(status, "nativeAnalyzeLuma");
    return new FrameMetrics((int) m[0], m[1], m[2]);
  }

  @Override
  public synchronized void close() {
    if (handle != 0) {
      nativeMatcherDestroy(handle);
      handle = 0;
    }
  }

  private static native int nativeExtractPan(char[] ocr, int len, char[] out, int[] meta);
  private static native int nativeExtractExpiry(char[] ocr, int len, int[] out);
  private static native int nativeExtractCardholderName(char[] ocr, int len, char[] out, int[] outLen);
  private static native int nativeExtractMrz(char[] ocr, int len, char[] outStrings, int[] outFlags);
  private static native boolean nativeLuhnValidate(char[] digits, int len);
  private static native int nativeAnalyzeLuma(ByteBuffer direct, int width, int height, int stride, float[] metrics);
  private static native long nativeMatcherCreate(float frameWidth, float frameHeight);
  private static native void nativeMatcherDestroy(long handle);
}
