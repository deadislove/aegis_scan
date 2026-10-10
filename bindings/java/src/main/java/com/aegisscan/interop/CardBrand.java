// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop;

/** Card scheme (values mirror AEGIS_BRAND_* in fintech_core_c.h). */
public enum CardBrand {
  UNKNOWN, VISA, MASTERCARD, AMEX, JCB;

  static CardBrand fromCode(int code) {
    CardBrand[] all = values();
    return code >= 0 && code < all.length ? all[code] : UNKNOWN;
  }
}
