package com.sajin.mizan.core;

/** Nonce is not greater than the last nonce of the account. */
public final class StaleNonceException extends OrderRejectedException {
  private static final long serialVersionUID = 1L;
  public static final int CODE = 5;

  public StaleNonceException(String message) {
    super(message);
  }

  @Override
  public int reasonCode() {
    return CODE;
  }
}
