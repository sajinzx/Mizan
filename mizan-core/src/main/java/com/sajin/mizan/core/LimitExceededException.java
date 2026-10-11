package com.sajin.mizan.core;

/** A value or balance would exceed a hard limit or overflow. */
public final class LimitExceededException extends OrderRejectedException {
  private static final long serialVersionUID = 1L;
  public static final int CODE = 3;

  public LimitExceededException(String message) {
    super(message);
  }

  @Override
  public int reasonCode() {
    return CODE;
  }
}
