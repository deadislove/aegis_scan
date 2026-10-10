// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop.jni;

import com.aegisscan.interop.FintechCoreException;
import com.aegisscan.interop.FrameMetrics;
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

    Pan(char[] digits, int brand) {
      this.digits = digits;
      this.brand = brand;
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
  private static native boolean nativeLuhnValidate(char[] digits, int len);
  private static native int nativeAnalyzeLuma(ByteBuffer direct, int width, int height, int stride, float[] metrics);
  private static native long nativeMatcherCreate(float frameWidth, float frameHeight);
  private static native void nativeMatcherDestroy(long handle);
}
