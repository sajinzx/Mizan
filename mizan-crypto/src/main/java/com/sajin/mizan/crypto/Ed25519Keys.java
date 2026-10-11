package com.sajin.mizan.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/** Ed25519 key encoding helpers. Public keys travel as X.509 SubjectPublicKeyInfo (44 bytes). */
final class Ed25519Keys {
  static final String ALGORITHM = "Ed25519";
  static final int PUBLIC_KEY_BYTES = 44;

  private Ed25519Keys() {}

  static PublicKey decodePublic(byte[] x509) throws GeneralSecurityException {
    if (x509.length != PUBLIC_KEY_BYTES) {
      throw new GeneralSecurityException("Ed25519 X.509 key must be 44 bytes: " + x509.length);
    }
    return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(x509));
  }
}
