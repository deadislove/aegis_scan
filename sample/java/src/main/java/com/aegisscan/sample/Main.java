// AegisScan Java sample: drives both interop options against the real
// native core.
//   Option A (FintechCoreInterop) -- JDK 21+ Foreign Function & Memory API.
//   Option B (FintechCoreJni)     -- legacy JNI fallback (also what Android uses).
// Run via `make run` from this directory, or see README.md for raw commands.
package com.aegisscan.sample;

import com.aegisscan.interop.FintechCoreInterop;
import com.aegisscan.interop.FrameMetrics;
import com.aegisscan.interop.SecurePan;
import com.aegisscan.interop.jni.FintechCoreJni;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class Main {

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--camera")) {
      runCameraLoop();
      return;
    }

    Path coreLib = resolveLib("libaegis_core");
    Path jniLib = resolveLib("libaegis_jni");

    // Pass real camera-recognized text as an argument (e.g. from the macOS
    // Camera Harness -- see ../macos/CameraHarness -- via
    // `make run ARGS="$(pbpaste)"`) to test against a real scan instead of
    // the built-in demo string below.
    String ocrText = args.length > 0 ? String.join(" ", args)
        // Two characters misread as letters ('I'/'l' for '1'); the core
        // repairs them but still requires most digits to already be genuine.
        : "JOHN DOE\nCARD 4111-111I-1111-111l EXP 12/29";
    System.out.println(args.length > 0 ? "Using OCR text from the command line:" : "Using built-in demo OCR text:");
    System.out.println("  \"" + ocrText + "\"");
    System.out.println();

    System.out.println("=== Option A: FintechCoreInterop (FFM / Project Panama) ===");
    try (FintechCoreInterop core = new FintechCoreInterop(coreLib, 1080f, 1920f)) {
      runFfmDemo(core, ocrText);
    }

    System.out.println();
    System.out.println("=== Option B: FintechCoreJni (legacy JNI fallback) ===");
    FintechCoreJni.load(jniLib.toString());
    try (FintechCoreJni jni = new FintechCoreJni(0, 0)) {
      runJniDemo(jni, ocrText);
    }
  }

  private static void runFfmDemo(FintechCoreInterop core, String ocrText) {
    System.out.println("native version: " + core.version());

    char[] goodPan = "4111111111111111".toCharArray();
    System.out.println("validateLuhn(4111111111111111) = " + core.validateLuhn(new String(goodPan)));

    try (SecurePan pan = core.extractPan(ocrText.toCharArray())) {
      if (pan == null) {
        System.out.println("extractPan: no PAN found");
      } else {
        System.out.println("extractPan found: " + pan + " brand=" + pan.brand());
      }
    }
    // Best-effort: scanned from the same OCR text, not validated by a
    // checksum the way the PAN is. extractCardDetails attaches both in one pass.
    var expiry = core.extractExpiry(ocrText);
    if (expiry != null) System.out.println("extractExpiry found: " + expiry.month() + "/" + expiry.year());
    String name = core.extractCardholderName(ocrText);
    if (name != null) System.out.println("extractCardholderName found: " + name);

    ByteBuffer luma = ByteBuffer.allocateDirect(64 * 64).order(ByteOrder.nativeOrder());
    for (int i = 0; i < luma.capacity(); i++) luma.put(i, (byte) 128);
    FrameMetrics metrics = core.analyzeLuma(luma, 64, 64, 64);
    System.out.printf("analyzeLuma: ok=%s meanLuma=%.1f focusScore=%.3f%n",
        metrics.ok(), metrics.meanLuma(), metrics.focusScore());

    var barcodes = List.of(new FintechCoreInterop.BarcodeItem("099123456789",
        new FintechCoreInterop.Box(100, 800, 300, 80)));
    var texts = List.of(
        new FintechCoreInterop.TextItem("THANK YOU FOR SHOPPING", new FintechCoreInterop.Box(80, 200, 400, 40)),
        new FintechCoreInterop.TextItem("VOUCHER 099123456789", new FintechCoreInterop.Box(100, 720, 320, 50)));
    List<FintechCoreInterop.MatchResult> matches = core.matchBarcodesWithText(barcodes, texts);
    System.out.println("matchBarcodesWithText: " + matches.size() + " match(es)");
    for (var m : matches) {
      System.out.println("  barcode \"" + m.barcode().payload() + "\" <-> text \"" + m.associatedText() + "\""
          + " (distance=" + m.normalizedDistance() + ", alignment=" + m.alignment() + ")");
    }
  }

  private static void runJniDemo(FintechCoreJni jni, String ocrText) {
    char[] digits = "4111111111111111".toCharArray();
    System.out.println("validateLuhn(4111111111111111) = " + jni.validateLuhn(digits));

    try (FintechCoreJni.Pan pan = jni.extractPan(ocrText.toCharArray())) {
      System.out.println(pan == null ? "extractPan: no PAN found" : "extractPan found: " + pan);
    }
    var expiry = jni.extractExpiry(ocrText.toCharArray());
    if (expiry != null) System.out.println("extractExpiry found: " + expiry.month() + "/" + expiry.year());
    String name = jni.extractCardholderName(ocrText.toCharArray());
    if (name != null) System.out.println("extractCardholderName found: " + name);

    ByteBuffer luma = ByteBuffer.allocateDirect(64 * 64).order(ByteOrder.nativeOrder());
    for (int i = 0; i < luma.capacity(); i++) luma.put(i, (byte) 128);
    FrameMetrics metrics = jni.analyzeLuma(luma, 64, 64, 64);
    System.out.printf("analyzeLuma: ok=%s meanLuma=%.1f focusScore=%.3f%n",
        metrics.ok(), metrics.meanLuma(), metrics.focusScore());
  }

  /**
   * Live camera mode ({@code make run ARGS=--camera}). Opens the Mac's
   * webcam directly from this Java program -- no separate app, no clipboard
   * bridge. Pixel capture and text/barcode recognition are NOT reimplemented
   * here (that's a platform Vision/ML Kit's job on iOS/Android, and would be
   * a multi-year effort to build from scratch); instead this shells out to
   * three small, well-known CLI tools that do exactly that:
   * <ul>
   *   <li>{@code imagesnap} -- grabs a frame from the webcam</li>
   *   <li>{@code tesseract} -- OCR engine (same engine the web sample uses, via WASM there)</li>
   *   <li>{@code zbarimg}   -- barcode decoder</li>
   * </ul>
   * Every frame's OCR/barcode output is then fed through this SDK's own real
   * {@code extractPan}/Luhn/repair logic -- the actual thing this sample
   * demonstrates.
   */
  private static void runCameraLoop() throws Exception {
    System.out.println("Live camera mode: capturing from this Mac's webcam, running real OCR");
    System.out.println("(tesseract) and barcode decoding (zbarimg) on each frame, then feeding the");
    System.out.println("result through this SDK's real extractPan/Luhn logic. Press Ctrl+C to stop.");
    System.out.println();

    requireTool("imagesnap", "brew install imagesnap");
    requireTool("tesseract", "brew install tesseract");
    requireTool("zbarimg", "brew install zbar");

    Path coreLib = resolveLib("libaegis_core");
    Path tempDir = Files.createTempDirectory("aegisscan-camera-sample");
    Path framePath = tempDir.resolve("frame.jpg");

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        Files.deleteIfExists(framePath);
        Files.deleteIfExists(tempDir);
      } catch (IOException ignored) {
        // Best-effort cleanup on exit.
      }
      System.out.println("Stopped.");
    }));

    try (FintechCoreInterop core = new FintechCoreInterop(coreLib, 1080f, 1920f)) {
      int frameNum = 0;
      while (true) {
        frameNum++;
        runProcess("imagesnap", "-w", "1", "-q", framePath.toString());
        if (!Files.exists(framePath)) {
          System.out.println("[" + frameNum + "] capture failed (no camera? permission denied?), retrying...");
          continue;
        }

        String ocrText = runProcessCapture("tesseract",
            framePath.toString(), "stdout", "-c", "tessedit_char_whitelist=0123456789");
        String barcodeText = runProcessCapture("zbarimg", "--raw", "-q", framePath.toString()).trim();
        Files.deleteIfExists(framePath);

        SecurePan framePan = ocrText.isBlank() ? null : core.extractPan(ocrText.toCharArray());
        if (framePan != null) {
          try (framePan) {
            System.out.println("[" + frameNum + "] CARD DETECTED: " + framePan + " brand=" + framePan.brand());
          }
        } else if (!barcodeText.isEmpty()) {
          System.out.println("[" + frameNum + "] barcode detected: " + barcodeText);
        } else {
          String preview = ocrText.replace('\n', ' ').trim();
          if (preview.length() > 40) preview = preview.substring(0, 40) + "...";
          System.out.println("[" + frameNum + "] no card/barcode yet (OCR saw: \"" + preview + "\")");
        }

        Thread.sleep(500);
      }
    }
  }

  private static void requireTool(String name, String installHint) {
    try {
      Process p = new ProcessBuilder(name, "--version").redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      p.waitFor();
    } catch (IOException e) {
      System.err.println("'" + name + "' not found on PATH. Install it with: " + installHint);
      System.exit(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void runProcess(String... command) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    p.waitFor();
  }

  private static String runProcessCapture(String... command) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(command).start();
    String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.getErrorStream().readAllBytes();
    p.waitFor();
    return stdout;
  }

  /** Resolves a library built by `core/Makefile` (`make lib` / `make jni`). */
  private static Path resolveLib(String baseName) {
    Path buildDir = Paths.get("..", "..", "core", "build").toAbsolutePath().normalize();
    for (String ext : new String[] {"dylib", "so", "dll"}) {
      Path candidate = buildDir.resolve(baseName + "." + ext);
      if (java.nio.file.Files.exists(candidate)) return candidate;
    }
    throw new IllegalStateException("Native library " + baseName + " not found under " + buildDir
        + " -- run `make lib jni` in core/ first (see sample/java/README.md)");
  }

  private Main() {}
}
