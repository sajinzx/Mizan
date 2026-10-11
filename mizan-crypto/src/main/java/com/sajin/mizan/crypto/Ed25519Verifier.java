package com.sajin.mizan.crypto;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;

/** {@link SignatureVerifier} using the JDK's {@code Ed25519} provider. Stateless. */
final class Ed25519Verifier implements SignatureVerifier {
  static final Ed25519Verifier INSTANCE = new Ed25519Verifier();

  private Ed25519Verifier() {}

  @Override
  public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
    if (publicKey == null || message == null || signature == null) {
      return false;
    }
    if (signature.length != SIGNATURE_BYTES) {
      return false;
    }
    try {
      PublicKey key = Ed25519Keys.decodePublic(publicKey);
      Signature s = Signature.getInstance(Ed25519Keys.ALGORITHM);
      s.initVerify(key);
      s.update(message);
      return s.verify(signature);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false; // malformed key or signature encoding: does not verify
    }
  }
}
