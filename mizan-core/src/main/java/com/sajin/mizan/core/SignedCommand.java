package com.sajin.mizan.core;

import java.util.Objects;

/**
 * A decoded, signed command envelope (§5.3). {@code raw} is the exact envelope bytes, including the
 * signature; {@code signature} is its trailing 64 bytes.
 *
 * <p>Arrays are not copied; callers must not mutate them after construction.
 */
@SuppressWarnings("ArrayRecordComponent") // byte[] by design (§5.4); compare with Arrays.equals
public record SignedCommand(
    int chainId, long accountId, long nonce, OrderCommand body, byte[] signature, byte[] raw) {
  public SignedCommand {
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(signature, "signature");
    Objects.requireNonNull(raw, "raw");
  }
}
