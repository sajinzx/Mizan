package com.sajin.mizan.crypto;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/**
 * Binary Merkle root over key-sorted leaves (§5.6, state root version 1).
 *
 * <ul>
 *   <li>leaf hash = {@code SHA256(0x00 || key:8 || value)}
 *   <li>node hash = {@code SHA256(0x01 || left || right)}
 *   <li>an odd node at any level is paired with itself
 *   <li>the empty tree is 32 zero bytes
 * </ul>
 *
 * <p>The distinct tag bytes separate leaves from nodes, so a leaf cannot be confused with a node.
 */
public final class SimpleMerkle {
  private static final byte LEAF = 0x00;
  private static final byte NODE = 0x01;

  private SimpleMerkle() {}

  /** One leaf. The value array is not copied. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Leaf(long key, byte[] value) {
    public Leaf {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * Root over {@code leaves}, which must be in strictly ascending key order.
   *
   * @throws IllegalArgumentException if keys are not strictly ascending
   */
  public static byte[] root(List<Leaf> leaves) {
    if (leaves.isEmpty()) {
      return new byte[Hashing.HASH_BYTES];
    }
    MessageDigest md = Hashing.newSha256();
    byte[][] level = new byte[leaves.size()][];
    long prev = 0;
    for (int i = 0; i < leaves.size(); i++) {
      Leaf leaf = leaves.get(i);
      if (i > 0 && leaf.key() <= prev) {
        throw new IllegalArgumentException("leaf keys must be strictly ascending at index " + i);
      }
      prev = leaf.key();
      level[i] = leafHash(md, leaf.key(), leaf.value());
    }
    return reduce(md, level);
  }

  /**
   * Root over an ordered list of items, using each item's index as its key. Used for {@code txRoot}
   * (§5.7).
   */
  public static byte[] rootOfList(List<byte[]> items) {
    if (items.isEmpty()) {
      return new byte[Hashing.HASH_BYTES];
    }
    MessageDigest md = Hashing.newSha256();
    byte[][] level = new byte[items.size()][];
    for (int i = 0; i < items.size(); i++) {
      level[i] = leafHash(md, i, items.get(i));
    }
    return reduce(md, level);
  }

  static byte[] leafHash(MessageDigest md, long key, byte[] value) {
    md.update(LEAF);
    md.update(ByteBuffer.allocate(Long.BYTES).putLong(key).array());
    md.update(value);
    return md.digest();
  }

  static byte[] nodeHash(MessageDigest md, byte[] left, byte[] right) {
    md.update(NODE);
    md.update(left);
    md.update(right);
    return md.digest();
  }

  private static byte[] reduce(MessageDigest md, byte[][] level) {
    int n = level.length;
    while (n > 1) {
      int next = 0;
      for (int i = 0; i < n; i += 2) {
        byte[] left = level[i];
        byte[] right = i + 1 < n ? level[i + 1] : left;
        level[next++] = nodeHash(md, left, right);
      }
      n = next;
    }
    return level[0];
  }
}
