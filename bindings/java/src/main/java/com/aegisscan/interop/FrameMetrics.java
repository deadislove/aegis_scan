// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop;

/**
 * Result of luma-plane validation. {@code status} mirrors
 * aegisscan::core::FrameStatus (0 = OK, 1 = null buffer, 2 = invalid dimensions,
 * 3 = invalid stride, 4 = buffer too small, 5 = unsupported aspect ratio,
 * 6 = too dark, 7 = too bright, 8 = blurry).
 */
public record FrameMetrics(int status, float meanLuma, float focusScore) {
  public boolean ok() { return status == 0; }
}
