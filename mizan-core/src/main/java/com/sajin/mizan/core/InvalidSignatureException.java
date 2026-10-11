package com.sajin.mizan.core;

/** Signature, chain id or signer identity does not verify. */
public final class InvalidSignatureException extends OrderRejectedException {
  private static final long serialVersionUID = 1L;
  public static final int CODE = 4;

  public InvalidSignatureException(String message) {
    super(message);
  }

  @Override
  public int reasonCode() {
    return CODE;
  }
}
