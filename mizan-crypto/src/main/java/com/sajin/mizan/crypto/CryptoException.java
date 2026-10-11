package com.sajin.mizan.crypto;

/** A cryptographic operation failed for environmental reasons (bad key material, provider). */
public class CryptoException extends Exception {
  private static final long serialVersionUID = 1L;

  public CryptoException(String message, Throwable cause) {
    super(message, cause);
  }
}
