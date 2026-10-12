package com.sajin.mizan.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SignatureVerifier} using the JDK's {@code Ed25519} provider. Thread-safe.
 *
 * <p>M10-T4: decoded public keys are kept in a small bounded map (there are few distinct keys:
 * accounts and validators), and each thread reuses one {@link Signature} object, instead of
 * decoding the X.509 key and looking up the provider on every call. The curve arithmetic itself is
 * unchanged.
 */
final class Ed25519Verifier implements SignatureVerifier {
  static final Ed25519Verifier INSTANCE = new Ed25519Verifier();
  static final int KEY_CACHE = 4_096;

  private final ConcurrentHashMap<ByteBuffer, PublicKey> keys = new ConcurrentHashMap<>();
  private final ThreadLocal<Signature> signatures =
      ThreadLocal.withInitial(
          () -> {
            try {
              return Signature.getInstance(Ed25519Keys.ALGORITHM);
            } catch (NoSuchAlgorithmException e) {
              throw new IllegalStateException("JDK without Ed25519", e);
            }
          });

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
      PublicKey key = key(publicKey);
      Signature s = signatures.get();
      s.initVerify(key);
      s.update(message);
      return s.verify(signature);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false; // malformed key or signature encoding: does not verify
    }
  }

  private PublicKey key(byte[] encoded) throws GeneralSecurityException {
    ByteBuffer k = ByteBuffer.wrap(encoded.clone());
    PublicKey cached = keys.get(k);
    if (cached != null) {
      return cached;
    }
    PublicKey decoded = Ed25519Keys.decodePublic(encoded); // throws on malformed keys: not cached
    if (keys.size() >= KEY_CACHE) {
      keys.clear(); // crude but bounded; refills with the keys in use
    }
    keys.put(k, decoded);
    return decoded;
  }
}
