// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop;

import java.util.Arrays;

/**
 * PAN held in a wipeable {@code char[]} (never a {@link String}). Use with
 * try-with-resources: {@link #close()} zeroes the digits deterministically,
 * without waiting for the GC. {@link #toString()} is redacted.
 */
public final class SecurePan implements AutoCloseable {
  private final char[] digits;
  private final CardBrand brand;
  private final String last4;
  private volatile boolean wiped;

  SecurePan(char[] digits, CardBrand brand) {
    this.digits = digits;
    this.brand = brand;
    this.last4 = digits.length >= 4 ? new String(digits, digits.length - 4, 4) : "";
  }

  public CardBrand brand() { return brand; }

  /** Last four digits (safe to display, PCI-DSS 3.4). */
  public String last4() { return last4; }

  public synchronized int length() { return wiped ? 0 : digits.length; }

  public boolean isWiped() { return wiped; }

  /** Copies the digits into a caller buffer, which the caller must wipe. */
  public synchronized void copyDigitsTo(char[] destination) {
    if (wiped) throw new IllegalStateException("SecurePan already wiped");
    if (destination.length < digits.length) throw new IllegalArgumentException("destination too small");
    System.arraycopy(digits, 0, destination, 0, digits.length);
  }

  @Override
  public synchronized void close() {
    Arrays.fill(digits, '\0');
    wiped = true;
  }

  @Override
  public String toString() { return "SecurePan(" + brand + ", ****" + last4 + ")"; }
}
