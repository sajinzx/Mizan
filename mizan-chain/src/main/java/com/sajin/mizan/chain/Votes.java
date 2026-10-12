package com.sajin.mizan.chain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Vote sign-bytes (§5.7): {@code "CBVOTE1" || chainId:4 || type:1 || height:8 || round:4 ||
 * blockHash} where {@code type} is 1 = prevote, 2 = precommit and a nil vote signs 32 zero bytes.
 */
public final class Votes {
  public static final int PREVOTE = 1;
  public static final int PRECOMMIT = 2;
  private static final byte[] PREFIX = "CBVOTE1".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] NIL = new byte[32];

  private Votes() {}

  public static byte[] signBytes(int chainId, int type, long height, int round, byte[] blockHash) {
    if (type != PREVOTE && type != PRECOMMIT) {
      throw new IllegalArgumentException("bad vote type " + type);
    }
    byte[] h = blockHash == null ? NIL : blockHash;
    if (h.length != 32) {
      throw new IllegalArgumentException("block hash must be 32 bytes");
    }
    return ByteBuffer.allocate(PREFIX.length + 4 + 1 + 8 + 4 + 32)
        .put(PREFIX)
        .putInt(chainId)
        .put((byte) type)
        .putLong(height)
        .putInt(round)
        .put(h)
        .array();
  }
}
