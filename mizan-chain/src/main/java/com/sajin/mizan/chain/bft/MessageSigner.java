package com.sajin.mizan.chain.bft;

import com.sajin.mizan.crypto.CryptoException;
import com.sajin.mizan.crypto.Signer;

/** Signs consensus messages as one validator. */
@FunctionalInterface
public interface MessageSigner {
  byte[] sign(byte[] message);

  /** Ed25519 with the validator's key; signing failures are fatal (the key is local). */
  static MessageSigner of(Signer signer) {
    return m -> {
      try {
        return signer.sign(m);
      } catch (CryptoException e) {
        throw new IllegalStateException("validator key cannot sign", e);
      }
    };
  }
}
