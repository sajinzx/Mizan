package com.sajin.mizan.core;

import java.util.Objects;

/**
 * Registers the signer's account with an X.509-encoded Ed25519 public key.
 *
 * <p>The array is not copied; callers must not mutate it after construction.
 */
@SuppressWarnings("ArrayRecordComponent") // byte[] by design (§5.4); compare with Arrays.equals
public record Register(byte[] pubKey) implements OrderCommand {
  public Register {
    Objects.requireNonNull(pubKey, "pubKey");
  }
}
