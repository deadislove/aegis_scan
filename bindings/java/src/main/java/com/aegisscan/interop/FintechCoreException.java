// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.interop;

/** Native failure. The message never contains input data, PANs or addresses. */
public final class FintechCoreException extends RuntimeException {
  private final int status;

  public FintechCoreException(int status, String operation) {
    super(operation + " failed with status " + status);
    this.status = status;
  }

  public int status() { return status; }
}
