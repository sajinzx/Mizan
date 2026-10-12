package com.sajin.mizan.chain;

import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.crypto.Hashing;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Block header (§5.7), 125 bytes, big-endian:
 *
 * <pre>
 * [version:1][chainId:4][height:8][round:4][prevHash:32][txRoot:32][stateRoot:32][proposer:4][timestampMillis:8]
 * </pre>
 *
 * <p>{@code blockHash = SHA256(header bytes)}. The timestamp is metadata only; the engine never
 * reads it. Arrays are not copied.
 */
@SuppressWarnings("ArrayRecordComponent")
public record BlockHeader(
    int version,
    int chainId,
    long height,
    int round,
    byte[] prevHash,
    byte[] txRoot,
    byte[] stateRoot,
    int proposer,
    long timestampMillis) {

  public static final int VERSION = 1;
  public static final int BYTES = 1 + 4 + 8 + 4 + 32 + 32 + 32 + 4 + 8;

  public BlockHeader {
    Objects.requireNonNull(prevHash, "prevHash");
    Objects.requireNonNull(txRoot, "txRoot");
    Objects.requireNonNull(stateRoot, "stateRoot");
    if (prevHash.length != 32 || txRoot.length != 32 || stateRoot.length != 32) {
      throw new IllegalArgumentException("hashes must be 32 bytes");
    }
  }

  public byte[] encode() {
    return ByteBuffer.allocate(BYTES)
        .put((byte) version)
        .putInt(chainId)
        .putLong(height)
        .putInt(round)
        .put(prevHash)
        .put(txRoot)
        .put(stateRoot)
        .putInt(proposer)
        .putLong(timestampMillis)
        .array();
  }

  public byte[] hash() {
    return Hashing.sha256(encode());
  }

  static BlockHeader read(ByteBuffer b) throws ProtocolException {
    try {
      int version = Byte.toUnsignedInt(b.get());
      if (version != VERSION) {
        throw new ProtocolException("unknown block version " + version);
      }
      int chainId = b.getInt();
      long height = b.getLong();
      int round = b.getInt();
      byte[] prev = new byte[32];
      byte[] tx = new byte[32];
      byte[] state = new byte[32];
      b.get(prev).get(tx).get(state);
      return new BlockHeader(
          version, chainId, height, round, prev, tx, state, b.getInt(), b.getLong());
    } catch (BufferUnderflowException e) {
      throw new ProtocolException("truncated block header");
    }
  }

  /** Field-wise equality (records compare arrays by identity). */
  public boolean sameAs(BlockHeader o) {
    return Arrays.equals(encode(), o.encode());
  }

  @Override
  public String toString() {
    return "BlockHeader[h="
        + height
        + " r="
        + round
        + " hash="
        + HexFormat.of().formatHex(hash(), 0, 6)
        + "…]";
  }
}
