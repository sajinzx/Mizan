package com.sajin.mizan.core;

/** Free balance is lower than what the command must escrow or spend. */
public final class InsufficientBalanceException extends OrderRejectedException {
  private static final long serialVersionUID = 1L;
  public static final int CODE = 2;

  public InsufficientBalanceException(String message) {
    super(message);
  }

  @Override
  public int reasonCode() {
    return CODE;
  }
}
