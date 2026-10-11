package com.sajin.mizan.crypto;

/**
 * Verifies Ed25519 signatures. Implementations are thread-safe and never throw for malformed input:
 * a bad key, message or signature simply does not verify.
 */
public interface SignatureVerifier {
  int SIGNATURE_BYTES = 64;

  /**
   * Returns whether {@code signature} is a valid signature of {@code message} under {@code
   * publicKey}.
   *
   * @param publicKey X.509-encoded Ed25519 public key (44 bytes)
   * @param message the signed bytes
   * @param signature the 64-byte signature
   */
  boolean verify(byte[] publicKey, byte[] message, byte[] signature);

  /** The JDK-backed verifier. */
  static SignatureVerifier ed25519() {
    return Ed25519Verifier.INSTANCE;
  }
}
