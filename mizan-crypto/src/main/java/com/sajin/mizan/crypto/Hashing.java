package com.sajin.mizan.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 helpers backed by the JDK provider. */
public final class Hashing {
  public static final int HASH_BYTES = 32;

  private Hashing() {}

  /** A fresh SHA-256 digest. Every compliant JDK provides SHA-256. */
  public static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static byte[] sha256(byte[] data) {
    return newSha256().digest(data);
  }

  /** SHA-256 of the concatenation of {@code parts}. */
  public static byte[] sha256(byte[]... parts) {
    MessageDigest md = newSha256();
    for (byte[] p : parts) {
      md.update(p);
    }
    return md.digest();
  }
}
