// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aegisscan.interop.FintechCoreInterop.BarcodeItem;
import com.aegisscan.interop.FintechCoreInterop.Box;
import com.aegisscan.interop.FintechCoreInterop.TextItem;
import com.aegisscan.interop.jni.FintechCoreJni;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Requires -Daegis.core.lib=<libaegis_core path> and -Daegis.jni.lib=<libaegis_jni path>. */
class InteropTest {
  private static Path coreLib() { return Path.of(System.getProperty("aegis.core.lib")); }

  @Test
  void ffmLuhnAndOcrRepair() {
    try (FintechCoreInterop core = new FintechCoreInterop(coreLib())) {
      assertEquals("1.0.0", core.version());
      assertTrue(core.validateLuhn("4111111111111111"));
      assertFalse(core.validateLuhn("4111111111111112"));
      try (SecurePan pan = core.extractPan("CARD 4O12 B888 88B8 1881".toCharArray())) {
        assertNotNull(pan);
        assertEquals(CardBrand.VISA, pan.brand());
        assertEquals("1881", pan.last4());
        assertFalse(pan.toString().contains("4012"));
        char[] buf = new char[19];
        pan.copyDigitsTo(buf);
        assertEquals("4012888888881881", new String(buf, 0, pan.length()));
        java.util.Arrays.fill(buf, '\0');
      }
      assertNull(core.extractPan("SOLO BOSS SOLO BOSS"));
      try (SecurePan pan = core.extractPan("N\u00ba 5555 SSSS 5555 4444")) {
        assertEquals(CardBrand.MASTERCARD, pan.brand());
      }
    }
  }

  @Test
  void ffmZeroCopyLumaAndCorruptBuffers() {
    try (FintechCoreInterop core = new FintechCoreInterop(coreLib());
         Arena arena = Arena.ofConfined()) {
      MemorySegment frame = arena.allocate(640 * 480);
      frame.fill((byte) 128);
      assertTrue(core.analyzeLuma(frame, 640, 480, 640).ok());
      assertEquals(4, core.analyzeLuma(frame.asSlice(0, 100), 640, 480, 640).status());
      MemorySegment wide = arena.allocate(5000 * 64);
      wide.fill((byte) 128);
      assertEquals(5, core.analyzeLuma(wide, 5000, 64, 5000).status());  // Aspect ratio.
      assertEquals(3, core.analyzeLuma(frame, 640, 480, 320).status());  // Bad stride.
      ByteBuffer direct = ByteBuffer.allocateDirect(320 * 240);
      assertEquals(6, core.analyzeLuma(direct, 320, 240, 320).status());  // All zeros: too dark.
      assertThrows(IllegalArgumentException.class,
          () -> core.analyzeLuma(ByteBuffer.allocate(16), 4, 4, 4));
    }
  }

  @Test
  void ffmSpatialMatcherAndDeterministicClose() {
    FintechCoreInterop core = new FintechCoreInterop(coreLib(), 1920, 1080);
    var results = core.matchBarcodesWithText(
        List.of(new BarcodeItem("VCH-12345678", new Box(100, 100, 300, 80))),
        List.of(new TextItem("Gift Voucher", new Box(100, 20, 200, 30)),
            new TextItem("VCH 1234 5678", new Box(110, 190, 280, 30))));
    assertEquals(1, results.size());
    assertEquals("VCH 1234 5678", results.get(0).associatedText());
    assertEquals(0, results.get(0).alignment());
    core.close();
    core.close();  // Idempotent.
    assertThrows(IllegalStateException.class, () -> core.matchBarcodesWithText(
        List.of(new BarcodeItem("1", new Box(0, 0, 1, 1))), List.of(new TextItem("1", new Box(0, 0, 1, 1)))));
  }

  @Test
  void ffmConcurrentInstances() throws Exception {
    AtomicInteger ok = new AtomicInteger();
    Thread[] threads = new Thread[8];
    for (int t = 0; t < threads.length; t++) {
      threads[t] = Thread.ofPlatform().start(() -> {
        try (FintechCoreInterop core = new FintechCoreInterop(coreLib())) {
          for (int i = 0; i < 200; i++) {
            try (SecurePan pan = core.extractPan("4111 1111 1111 1111".toCharArray())) {
              if (pan != null && pan.length() == 16) ok.incrementAndGet();
            }
          }
        }
      });
    }
    for (Thread th : threads) th.join();
    assertEquals(1600, ok.get());
  }

  @Test
  void jniFallback() {
    FintechCoreJni.load(System.getProperty("aegis.jni.lib"));
    try (FintechCoreJni jni = new FintechCoreJni(0, 0)) {
      assertTrue(jni.validateLuhn("378282246310005".toCharArray()));
      assertFalse(jni.validateLuhn("378282246310006".toCharArray()));
      try (FintechCoreJni.Pan pan = jni.extractPan("3782 B22463 10005".toCharArray())) {
        assertNotNull(pan);
        assertEquals(3, pan.brand);
        assertEquals("0005", pan.last4());
      }
      assertNull(jni.extractPan("HELLO".toCharArray()));
      ByteBuffer direct = ByteBuffer.allocateDirect(640 * 480);
      for (int i = 0; i < direct.capacity(); i++) direct.put(i, (byte) 128);
      assertTrue(jni.analyzeLuma(direct, 640, 480, 640).ok());
      assertThrows(IllegalArgumentException.class, () -> jni.analyzeLuma(ByteBuffer.allocate(16), 4, 4, 4));
    }
  }
}
