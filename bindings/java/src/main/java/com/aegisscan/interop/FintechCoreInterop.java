// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Option A: JDK 21+ Foreign Function &amp; Memory API (Project Panama) binding
 * to {@code libaegis_core}.
 *
 * <ul>
 *   <li>Every call allocates its temporary off-heap memory in a confined
 *       {@link Arena} (try-with-resources), so it's freed when the call
 *       returns, never by the GC.</li>
 *   <li>Native PAN buffers are zeroed with {@code aegis_secure_zero} before
 *       their arena closes.</li>
 *   <li>Images are passed as off-heap segments or direct ByteBuffers: zero copy.</li>
 *   <li>{@link #close()} frees the native matcher handle and unloads the library.</li>
 *   <li>Nothing is logged; exceptions carry only status codes.</li>
 * </ul>
 * Stateless calls are thread-safe; matching is {@code synchronized}.
 */
public final class FintechCoreInterop implements AutoCloseable {

  public static final int OK = 0;
  public static final int NOT_FOUND = 1;
  public static final int PAN_BUFFER_SIZE = 20;
  public static final int NAME_BUFFER_SIZE = 64;

  /** {@code aegis_item}: {const char*, int32, 4 floats} + tail padding to 8. */
  static final StructLayout ITEM = MemoryLayout.structLayout(
      ADDRESS.withName("text"), JAVA_INT.withName("text_len"),
      JAVA_FLOAT.withName("x"), JAVA_FLOAT.withName("y"),
      JAVA_FLOAT.withName("width"), JAVA_FLOAT.withName("height"),
      MemoryLayout.paddingLayout(4));
  /** {@code aegis_match}: six 32-bit fields. */
  static final StructLayout MATCH = MemoryLayout.structLayout(
      JAVA_INT.withName("barcode_index"), JAVA_INT.withName("text_index"),
      JAVA_FLOAT.withName("score"), JAVA_FLOAT.withName("normalized_distance"),
      JAVA_INT.withName("overlap_chars"), JAVA_INT.withName("alignment"));
  /** {@code aegis_frame_metrics}. */
  static final StructLayout METRICS = MemoryLayout.structLayout(
      JAVA_INT.withName("status"), JAVA_FLOAT.withName("mean_luma"),
      JAVA_FLOAT.withName("focus_score"));
  /** {@code aegis_mrz}: fixed-width UTF-8 byte fields + 4 check-valid flags,
   * padded to 4-byte-align the trailing ints (same as the C struct). */
  static final StructLayout MRZ = MemoryLayout.structLayout(
      MemoryLayout.sequenceLayout(10, JAVA_BYTE).withName("document_number"),
      MemoryLayout.sequenceLayout(4, JAVA_BYTE).withName("nationality"),
      MemoryLayout.sequenceLayout(7, JAVA_BYTE).withName("date_of_birth"),
      MemoryLayout.sequenceLayout(7, JAVA_BYTE).withName("expiry_date"),
      JAVA_BYTE.withName("sex"),
      MemoryLayout.sequenceLayout(40, JAVA_BYTE).withName("surname"),
      MemoryLayout.sequenceLayout(40, JAVA_BYTE).withName("given_names"),
      MemoryLayout.paddingLayout(3),
      JAVA_INT.withName("document_number_check_valid"),
      JAVA_INT.withName("date_of_birth_check_valid"),
      JAVA_INT.withName("expiry_check_valid"),
      JAVA_INT.withName("composite_check_valid"));

  public record Box(float x, float y, float width, float height) {}
  public record BarcodeItem(String payload, Box box) {}
  public record TextItem(String text, Box box) {}
  public record MatchResult(BarcodeItem barcode, String associatedText,
                            float normalizedDistance, int alignment) {}

  private final Arena libraryArena;
  private final MethodHandle version;
  private final MethodHandle luhnValidate;
  private final MethodHandle extractPanUtf16;
  private final MethodHandle extractExpiry;
  private final MethodHandle extractCardholderName;
  private final MethodHandle extractMrz;
  private final MethodHandle analyzeLuma;
  private final MethodHandle matcherDestroy;
  private final MethodHandle matcherMatch;
  private final MethodHandle secureZero;
  private MemorySegment matcher;

  /** Loads from an explicit path, or from java.library.path when null. */
  public FintechCoreInterop(Path libraryPath) {
    this(libraryPath, 0f, 0f);
  }

  public FintechCoreInterop(Path libraryPath, float frameWidth, float frameHeight) {
    libraryArena = Arena.ofShared();
    try {
      SymbolLookup lookup = libraryPath != null
          ? SymbolLookup.libraryLookup(libraryPath, libraryArena)
          : SymbolLookup.libraryLookup(System.mapLibraryName("aegis_core"), libraryArena);
      Linker linker = Linker.nativeLinker();
      version = bind(linker, lookup, "aegis_version", FunctionDescriptor.of(ADDRESS));
      luhnValidate = bind(linker, lookup, "aegis_luhn_validate",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
      extractPanUtf16 = bind(linker, lookup, "aegis_extract_pan_utf16",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
      extractExpiry = bind(linker, lookup, "aegis_extract_expiry",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
      extractCardholderName = bind(linker, lookup, "aegis_extract_cardholder_name",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
      extractMrz = bind(linker, lookup, "aegis_extract_mrz",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
      analyzeLuma = bind(linker, lookup, "aegis_analyze_luma",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
      MethodHandle matcherCreate = bind(linker, lookup, "aegis_matcher_create",
          FunctionDescriptor.of(ADDRESS, JAVA_FLOAT, JAVA_FLOAT));
      matcherDestroy = bind(linker, lookup, "aegis_matcher_destroy", FunctionDescriptor.ofVoid(ADDRESS));
      matcherMatch = bind(linker, lookup, "aegis_matcher_match", FunctionDescriptor.of(JAVA_INT,
          ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
      secureZero = bind(linker, lookup, "aegis_secure_zero",
          FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
      matcher = (MemorySegment) matcherCreate.invokeExact(frameWidth, frameHeight);
      if (matcher.address() == 0) throw new FintechCoreException(-3, "aegis_matcher_create");
    } catch (RuntimeException | Error e) {
      libraryArena.close();
      throw e;
    } catch (Throwable t) {
      libraryArena.close();
      throw new IllegalStateException("Failed to initialize native core", t);
    }
  }

  private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name,
                                   FunctionDescriptor fd) {
    MemorySegment symbol = lookup.find(name)
        .orElseThrow(() -> new UnsatisfiedLinkError("Missing native symbol " + name));
    return linker.downcallHandle(symbol, fd);
  }

  /** Native core version string. */
  public String version() {
    try {
      // Manual NUL scan: portable across the JDK 21 preview and JDK 22+ final API.
      MemorySegment p = ((MemorySegment) version.invokeExact()).reinterpret(64);
      StringBuilder sb = new StringBuilder();
      for (long i = 0; i < p.byteSize(); i++) {
        byte b = p.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i);
        if (b == 0) break;
        sb.append((char) b);
      }
      return sb.toString();
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Standard Luhn Mod-10 over ASCII digits. */
  public boolean validateLuhn(CharSequence digits) {
    int n = digits.length();
    if (n < 2 || n > 64) return false;
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment buf = arena.allocate(n);
      for (int i = 0; i < n; i++) {
        char c = digits.charAt(i);
        buf.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) (c < 0x80 ? c : '~'));
      }
      try {
        return (int) luhnValidate.invokeExact(buf, n) == 1;
      } finally {
        secureZero.invokeExact(buf, (long) n);
      }
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /**
   * Repairs OCR text and extracts the first valid PAN. The input is passed as
   * UTF-16 (one bulk copy into a confined arena, wiped before release).
   * Pass a {@code char[]} rather than a String so the caller can wipe it too.
   *
   * @return the PAN (close it!) or null when none was found.
   */
  public SecurePan extractPan(char[] rawOcr) {
    try (Arena arena = Arena.ofConfined()) {
      long inBytes = (long) rawOcr.length * Character.BYTES;
      MemorySegment in = arena.allocate(Math.max(2, inBytes), 2);
      MemorySegment.copy(rawOcr, 0, in, JAVA_CHAR, 0, rawOcr.length);
      MemorySegment out = arena.allocate((long) PAN_BUFFER_SIZE * Character.BYTES, 2);
      MemorySegment len = arena.allocate(JAVA_INT);
      MemorySegment brand = arena.allocate(JAVA_INT);
      try {
        int status = (int) extractPanUtf16.invokeExact(in, rawOcr.length, out, PAN_BUFFER_SIZE, len, brand);
        if (status == NOT_FOUND) return null;
        if (status != OK) throw new FintechCoreException(status, "aegis_extract_pan_utf16");
        int n = len.get(JAVA_INT, 0);
        char[] digits = new char[n];
        MemorySegment.copy(out, JAVA_CHAR, 0, digits, 0, n);
        return new SecurePan(digits, CardBrand.fromCode(brand.get(JAVA_INT, 0)));
      } finally {
        secureZero.invokeExact(in, in.byteSize());
        secureZero.invokeExact(out, out.byteSize());
      }
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Convenience overload. Strings can't be wiped, so prefer {@code char[]}. */
  public SecurePan extractPan(CharSequence rawOcr) {
    char[] copy = rawOcr.toString().toCharArray();
    try {
      return extractPan(copy);
    } finally {
      java.util.Arrays.fill(copy, '\0');
    }
  }

  /**
   * Scans for the first MM/YY or MM/YYYY pattern in raw OCR text. Unlike
   * {@link #extractPan}, there's no checksum to validate an expiry date
   * against -- the month range check (01-12) is the only guard against an
   * obviously-wrong match. UTF-8 only: there's no UTF-16 native entry point
   * for this call.
   */
  public ExpiryDate extractExpiry(CharSequence rawOcr) {
    byte[] utf8 = rawOcr.toString().getBytes(StandardCharsets.UTF_8);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment in = arena.allocate(Math.max(1, utf8.length));
      MemorySegment.copy(utf8, 0, in, JAVA_BYTE, 0, utf8.length);
      MemorySegment out = arena.allocate(8);
      int status = (int) extractExpiry.invokeExact(in, utf8.length, out);
      if (status == NOT_FOUND) return null;
      if (status != OK) throw new FintechCoreException(status, "aegis_extract_expiry");
      return new ExpiryDate(out.get(JAVA_INT, 0), out.get(JAVA_INT, 4));
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /**
   * Best-effort cardholder name: the longest line in the raw OCR text that
   * looks like a printed name (all uppercase letters/spaces, 2+ words, no
   * known bank/network word). Markedly less reliable than {@link #extractPan}
   * or {@link #extractExpiry} -- there's no structural signal to validate
   * against. A name alone isn't PCI-DSS cardholder data, so this returns a
   * plain {@link String}. UTF-8 only: there's no UTF-16 native entry point
   * for this call.
   */
  public String extractCardholderName(CharSequence rawOcr) {
    byte[] utf8 = rawOcr.toString().getBytes(StandardCharsets.UTF_8);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment in = arena.allocate(Math.max(1, utf8.length));
      MemorySegment.copy(utf8, 0, in, JAVA_BYTE, 0, utf8.length);
      MemorySegment out = arena.allocate(NAME_BUFFER_SIZE);
      MemorySegment outLen = arena.allocate(JAVA_INT);
      int status = (int) extractCardholderName.invokeExact(in, utf8.length, out, NAME_BUFFER_SIZE, outLen);
      if (status == NOT_FOUND) return null;
      if (status != OK) throw new FintechCoreException(status, "aegis_extract_cardholder_name");
      int n = outLen.get(JAVA_INT, 0);
      byte[] name = new byte[n];
      MemorySegment.copy(out, JAVA_BYTE, 0, name, 0, n);
      return new String(name, StandardCharsets.UTF_8);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /**
   * Scans for a TD3 (passport, 2 lines x 44 chars) or TD1 (ID card, 3 lines
   * x 30 chars) MRZ block in raw OCR text. Returns null only when no
   * MRZ-shaped line run is found at all; when one is found but its check
   * digits fail, the fields are still returned with their *CheckValid
   * flags false. UTF-8 only, same reason as {@link #extractExpiry}.
   */
  public MrzResult extractMrz(CharSequence rawOcr) {
    byte[] utf8 = rawOcr.toString().getBytes(StandardCharsets.UTF_8);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment in = arena.allocate(Math.max(1, utf8.length));
      MemorySegment.copy(utf8, 0, in, JAVA_BYTE, 0, utf8.length);
      MemorySegment out = arena.allocate(MRZ);
      int status = (int) extractMrz.invokeExact(in, utf8.length, out);
      if (status == NOT_FOUND) return null;
      if (status != OK) throw new FintechCoreException(status, "aegis_extract_mrz");

      return new MrzResult(
          mrzField(out, "document_number"), mrzField(out, "nationality"),
          mrzField(out, "date_of_birth"), mrzField(out, "expiry_date"),
          (char) out.get(JAVA_BYTE, MRZ.byteOffset(PathElement.groupElement("sex"))),
          mrzField(out, "surname"), mrzField(out, "given_names"),
          mrzFlag(out, "document_number_check_valid"), mrzFlag(out, "date_of_birth_check_valid"),
          mrzFlag(out, "expiry_check_valid"), mrzFlag(out, "composite_check_valid"));
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /**
   * PAN + expiry + cardholder name from one OCR pass. {@link #extractPan}
   * stays PAN-only for callers that don't want the extra scans.
   */
  public SecurePan extractCardDetails(char[] rawOcr) {
    SecurePan pan = extractPan(rawOcr);
    if (pan == null) return null;
    String text = new String(rawOcr);
    ExpiryDate expiry = extractExpiry(text);
    String name = extractCardholderName(text);
    if (expiry == null && name == null) return pan;
    char[] digits = new char[pan.length()];
    try {
      pan.copyDigitsTo(digits);
      return new SecurePan(digits, pan.brand(), expiry, name);
    } finally {
      pan.close();
    }
  }

  /** Convenience overload. Strings can't be wiped, so prefer {@code char[]}. */
  public SecurePan extractCardDetails(CharSequence rawOcr) {
    char[] copy = rawOcr.toString().toCharArray();
    try {
      return extractCardDetails(copy);
    } finally {
      java.util.Arrays.fill(copy, '\0');
    }
  }

  /** Zero-copy luma analysis on an off-heap segment. */
  public FrameMetrics analyzeLuma(MemorySegment luma, int width, int height, int rowStride) {
    if (!luma.isNative()) throw new IllegalArgumentException("luma must be an off-heap segment");
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment m = arena.allocate(METRICS);
      int status = (int) analyzeLuma.invokeExact(luma, luma.byteSize(), width, height, rowStride, m);
      if (status != OK) throw new FintechCoreException(status, "aegis_analyze_luma");
      return new FrameMetrics(m.get(JAVA_INT, 0), m.get(JAVA_FLOAT, 4), m.get(JAVA_FLOAT, 8));
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Zero-copy luma analysis on a direct ByteBuffer (heap buffers are refused). */
  public FrameMetrics analyzeLuma(ByteBuffer directLuma, int width, int height, int rowStride) {
    if (!directLuma.isDirect()) throw new IllegalArgumentException("ByteBuffer must be direct (zero-copy)");
    return analyzeLuma(MemorySegment.ofBuffer(directLuma), width, height, rowStride);
  }

  /** One-to-one barcode/text association via the native SpatialMatcher. */
  public synchronized List<MatchResult> matchBarcodesWithText(List<BarcodeItem> barcodes,
                                                              List<TextItem> texts) {
    ensureOpen();
    if (barcodes.isEmpty() || texts.isEmpty()) return List.of();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bItems = allocateArray(arena, ITEM, barcodes.size());
      MemorySegment tItems = allocateArray(arena, ITEM, texts.size());
      for (int i = 0; i < barcodes.size(); i++) {
        writeItem(arena, bItems, i, barcodes.get(i).payload(), barcodes.get(i).box());
      }
      for (int i = 0; i < texts.size(); i++) {
        writeItem(arena, tItems, i, texts.get(i).text(), texts.get(i).box());
      }
      int capacity = Math.min(barcodes.size(), texts.size());
      MemorySegment out = allocateArray(arena, MATCH, capacity);
      MemorySegment count = arena.allocate(JAVA_INT);
      int status = (int) matcherMatch.invokeExact(matcher, bItems, barcodes.size(), tItems,
          texts.size(), out, capacity, count);
      if (status != OK) throw new FintechCoreException(status, "aegis_matcher_match");
      int n = count.get(JAVA_INT, 0);
      List<MatchResult> results = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        long base = i * MATCH.byteSize();
        results.add(new MatchResult(barcodes.get(out.get(JAVA_INT, base)),
            texts.get(out.get(JAVA_INT, base + 4)).text(),
            out.get(JAVA_FLOAT, base + 12), out.get(JAVA_INT, base + 20)));
      }
      return results;
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  /** Reads one of {@link #MRZ}'s fixed-width byte fields as a NUL-trimmed UTF-8 string. */
  private static String mrzField(MemorySegment mrz, String name) {
    PathElement element = PathElement.groupElement(name);
    long offset = MRZ.byteOffset(element);
    int size = (int) MRZ.select(element).byteSize();
    byte[] bytes = new byte[size];
    MemorySegment.copy(mrz, JAVA_BYTE, offset, bytes, 0, size);
    int n = 0;
    while (n < bytes.length && bytes[n] != 0) n++;
    return new String(bytes, 0, n, StandardCharsets.UTF_8);
  }

  /** Reads one of {@link #MRZ}'s int check-valid fields as a boolean. */
  private static boolean mrzFlag(MemorySegment mrz, String name) {
    return mrz.get(JAVA_INT, MRZ.byteOffset(PathElement.groupElement(name))) != 0;
  }

  private static void writeItem(Arena arena, MemorySegment items, int index, String text, Box box) {
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    MemorySegment str = arena.allocate(Math.max(1, utf8.length));
    MemorySegment.copy(utf8, 0, str, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, utf8.length);
    long base = index * ITEM.byteSize();
    items.set(ADDRESS, base, str);
    items.set(JAVA_INT, base + 8, utf8.length);
    items.set(JAVA_FLOAT, base + 12, box.x());
    items.set(JAVA_FLOAT, base + 16, box.y());
    items.set(JAVA_FLOAT, base + 20, box.width());
    items.set(JAVA_FLOAT, base + 24, box.height());
  }

  /** Array allocation via (size, alignment): identical on JDK 21 preview and 22+. */
  private static MemorySegment allocateArray(Arena arena, MemoryLayout layout, long count) {
    return arena.allocate(layout.byteSize() * Math.max(1, count), layout.byteAlignment());
  }

  private void ensureOpen() {
    if (matcher == null) throw new IllegalStateException("FintechCoreInterop is closed");
  }

  /** Frees the native matcher and unloads the library. Idempotent. */
  @Override
  public synchronized void close() {
    if (matcher == null) return;
    try {
      matcherDestroy.invokeExact(matcher);
    } catch (Throwable t) {
      throw rethrow(t);
    } finally {
      matcher = null;
      libraryArena.close();
    }
  }

  private static RuntimeException rethrow(Throwable t) {
    if (t instanceof RuntimeException r) return r;
    if (t instanceof Error e) throw e;
    return new IllegalStateException("Native call failed", t);
  }
}
