package com.sajin.mizan.core;

/** Malformed or semantically invalid command: unknown instrument, bad tick, bad TIF, etc. */
public final class InvalidOrderException extends OrderRejectedException {
  private static final long serialVersionUID = 1L;
  public static final int CODE = 1;

  public InvalidOrderException(String message) {
    super(message);
  }

  @Override
  public int reasonCode() {
    return CODE;
  }
}
