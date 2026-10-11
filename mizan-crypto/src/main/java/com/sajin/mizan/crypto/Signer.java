package com.sajin.mizan.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.Objects;

/** Holds an Ed25519 key pair and signs messages with the JDK provider. Thread-safe. */
public final class Signer {
  public static final int SEED_BYTES = 32;

  private final PrivateKey privateKey;
  private final byte[] publicKey;

  private Signer(KeyPair pair) {
    this.privateKey = pair.getPrivate();
    this.publicKey = pair.getPublic().getEncoded();
    if (publicKey.length != Ed25519Keys.PUBLIC_KEY_BYTES) {
      throw new IllegalArgumentException("not an Ed25519 key pair");
    }
  }

  /** A fresh key pair from the platform's strong randomness. */
  public static Signer generate() throws CryptoException {
    return fromRandom(new SecureRandom());
  }

  /** Wraps an existing JDK Ed25519 key pair. */
  public static Signer fromKeyPair(KeyPair pair) {
    Objects.requireNonNull(pair, "pair");
    if (!Ed25519Keys.ALGORITHM.equals(pair.getPrivate().getAlgorithm())
        && !"EdDSA".equals(pair.getPrivate().getAlgorithm())) {
      throw new IllegalArgumentException("not an Ed25519 key pair");
    }
    return new Signer(pair);
  }

  /**
   * Deterministic key pair from a 32-byte RFC 8032 private key (seed). The JDK derives the public
   * key; the seed is fed through the generator's randomness source. For tests, fixtures and
   * reproducible demos only. Real keys come from {@link #generate()}.
   */
  public static Signer fromSeed(byte[] seed) throws CryptoException {
    if (seed.length != SEED_BYTES) {
      throw new IllegalArgumentException("Ed25519 seed must be 32 bytes");
    }
    return fromRandom(new FixedBytesRandom(seed));
  }

  private static Signer fromRandom(SecureRandom random) throws CryptoException {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance(Ed25519Keys.ALGORITHM);
      g.initialize(255, random);
      return new Signer(g.generateKeyPair());
    } catch (GeneralSecurityException e) {
      throw new CryptoException("Ed25519 key generation failed", e);
    }
  }

  /** X.509-encoded public key (44 bytes). Returns a copy. */
  public byte[] publicKey() {
    return publicKey.clone();
  }

  /** 64-byte Ed25519 signature over {@code message}. */
  public byte[] sign(byte[] message) throws CryptoException {
    try {
      Signature s = Signature.getInstance(Ed25519Keys.ALGORITHM);
      s.initSign(privateKey);
      s.update(message);
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("Ed25519 signing failed", e);
    }
  }

  /** Yields the given bytes as its only randomness, so key generation becomes a pure function. */
  private static final class FixedBytesRandom extends SecureRandom {
    private static final long serialVersionUID = 1L;
    private final byte[] bytes;
    private int used;

    FixedBytesRandom(byte[] bytes) {
      this.bytes = bytes.clone();
    }

    @Override
    public void nextBytes(byte[] out) {
      if (used + out.length > bytes.length) {
        throw new IllegalStateException("seed exhausted");
      }
      System.arraycopy(bytes, used, out, 0, out.length);
      used += out.length;
    }

    @Override
    public byte[] generateSeed(int n) {
      byte[] b = new byte[n];
      nextBytes(b);
      return b;
    }
  }

  @Override
  public String toString() {
    return "Signer[publicKey=" + Arrays.toString(Arrays.copyOfRange(publicKey, 12, 16)) + "…]";
  }
}
