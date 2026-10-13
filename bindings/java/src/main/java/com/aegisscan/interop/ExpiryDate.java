// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop;

/** A parsed MM/YY (or MM/YYYY) expiry, as printed on the card. */
public record ExpiryDate(int month, int year) {}
