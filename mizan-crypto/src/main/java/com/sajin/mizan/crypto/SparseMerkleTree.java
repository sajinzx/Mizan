package com.sajin.mizan.crypto;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A persistent (immutable, structurally shared) sparse Merkle tree over 64-bit keys (§5.6, M10-T2).
 *
 * <p><b>Hash definition</b> (the logical tree has depth 64; the root is at height 64, leaves at
 * height 0; the child taken at height {@code h} is bit {@code h-1} of the key, so the root splits
 * on the most significant bit):
 *
 * <pre>
 * leaf(key, value)     = SHA256(0x00 || key:8 || value)        (as in SimpleMerkle)
 * node(left, right)    = SHA256(0x01 || left || right)
 * EMPTY[0]             = 32 zero bytes
 * EMPTY[h + 1]         = node(EMPTY[h], EMPTY[h])               (default hash of an empty subtree)
 * </pre>
 *
 * <p><b>Storage.</b> A subtree containing exactly one key is stored as one {@code Single} node that
 * caches the hash it would have in the full tree, and empty subtrees are {@code null}; only
 * subtrees with two or more keys are {@code Branch} nodes. Memory is therefore O(keys), and {@link
 * #update} returns a new tree sharing every untouched node with this one, so copying a tree (and
 * the {@code ExecutionCore} that owns it) is O(1).
 *
 * <p><b>Batching.</b> {@link #update} takes all dirty keys of a block at once, sorted, and rebuilds
 * each affected node exactly once, so paths shared by several dirty keys are hashed once.
 */
public final class SparseMerkleTree {
  public static final int DEPTH = 64;
  private static final byte[][] EMPTY = new byte[DEPTH + 1][];

  static {
    EMPTY[0] = new byte[Hashing.HASH_BYTES];
    for (int h = 0; h < DEPTH; h++) {
      EMPTY[h + 1] = node(EMPTY[h], EMPTY[h]);
    }
  }

  /** The tree with no keys. */
  public static final SparseMerkleTree EMPTY_TREE = new SparseMerkleTree(null);

  private sealed interface Node permits Single, Branch {
    byte[] hash();
  }

  /** A subtree of height {@code height} holding exactly one key. */
  @SuppressWarnings("ArrayRecordComponent") // internal, never mutated or compared
  private record Single(long key, byte[] leafHash, int height, byte[] hash) implements Node {}

  /** A subtree with at least two keys; a {@code null} child is an empty subtree. */
  @SuppressWarnings("ArrayRecordComponent")
  private record Branch(Node left, Node right, byte[] hash) implements Node {}

  private final Node root;

  private SparseMerkleTree(Node root) {
    this.root = root;
  }

  /** The root hash ({@code EMPTY[64]} for an empty tree). */
  public byte[] root() {
    return (root == null ? EMPTY[DEPTH] : root.hash()).clone();
  }

  /** The default hash of an empty subtree of height {@code h}. */
  public static byte[] emptyHash(int h) {
    return EMPTY[h].clone();
  }

  /** {@code SHA256(0x00 || key || value)}. */
  public static byte[] leafHash(long key, byte[] value) {
    return Hashing.sha256(new byte[] {0x00}, ByteBuffer.allocate(8).putLong(key).array(), value);
  }

  static byte[] node(byte[] left, byte[] right) {
    return Hashing.sha256(new byte[] {0x01}, left, right);
  }

  /**
   * A new tree with each {@code keys[i]} set to the leaf hash {@code leafHashes[i]}, or removed if
   * that is {@code null}. {@code keys} must be strictly increasing as <em>unsigned</em> values (see
   * {@link #sortUnique}).
   */
  public SparseMerkleTree update(long[] keys, byte[][] leafHashes) {
    if (keys.length != leafHashes.length) {
      throw new IllegalArgumentException("keys and hashes differ in length");
    }
    for (int i = 1; i < keys.length; i++) {
      if (Long.compareUnsigned(keys[i - 1], keys[i]) >= 0) {
        throw new IllegalArgumentException("keys must be strictly increasing (unsigned)");
      }
    }
    if (keys.length == 0) {
      return this;
    }
    return new SparseMerkleTree(build(root, DEPTH, keys, leafHashes, 0, keys.length));
  }

  /** Sorts {@code keys} as unsigned values and drops duplicates (returns a new array). */
  public static long[] sortUnique(long[] keys, int n) {
    long[] k = Arrays.copyOf(keys, n);
    for (int i = 0; i < n; i++) {
      k[i] ^= Long.MIN_VALUE; // unsigned order == signed order of the flipped values
    }
    Arrays.sort(k);
    int m = 0;
    for (int i = 0; i < n; i++) {
      if (m == 0 || k[i] != k[m - 1]) {
        k[m++] = k[i];
      }
    }
    for (int i = 0; i < m; i++) {
      k[i] ^= Long.MIN_VALUE;
    }
    return Arrays.copyOf(k, m);
  }

  private static Node build(Node n, int h, long[] keys, byte[][] leaves, int lo, int hi) {
    if (lo == hi) {
      return n;
    }
    if (n instanceof Single s) {
      // Re-insert the existing key into this sub-batch unless the batch rewrites it.
      int at = searchUnsigned(keys, lo, hi, s.key());
      n = null;
      if (at < 0) {
        int ins = -at - 1;
        long[] k = new long[hi - lo + 1];
        byte[][] v = new byte[hi - lo + 1][];
        System.arraycopy(keys, lo, k, 0, ins - lo);
        System.arraycopy(leaves, lo, v, 0, ins - lo);
        k[ins - lo] = s.key();
        v[ins - lo] = s.leafHash();
        System.arraycopy(keys, ins, k, ins - lo + 1, hi - ins);
        System.arraycopy(leaves, ins, v, ins - lo + 1, hi - ins);
        keys = k;
        leaves = v;
        lo = 0;
        hi = k.length;
      }
    }
    if (n == null && hi - lo == 1) {
      return leaves[lo] == null ? null : single(keys[lo], leaves[lo], h);
    }
    // Two or more keys here, or a branch: split on bit h-1.
    long bit = 1L << (h - 1);
    int mid = lo;
    while (mid < hi && (keys[mid] & bit) == 0) {
      mid++;
    }
    Branch b = (Branch) n;
    Node left = build(b == null ? null : b.left(), h - 1, keys, leaves, lo, mid);
    Node right = build(b == null ? null : b.right(), h - 1, keys, leaves, mid, hi);
    return join(left, right, h);
  }

  /** {@link Arrays#binarySearch} semantics over unsigned order. */
  private static int searchUnsigned(long[] a, int lo, int hi, long key) {
    int l = lo;
    int h = hi - 1;
    while (l <= h) {
      int m = (l + h) >>> 1;
      int c = Long.compareUnsigned(a[m], key);
      if (c < 0) {
        l = m + 1;
      } else if (c > 0) {
        h = m - 1;
      } else {
        return m;
      }
    }
    return -(l + 1);
  }

  /**
   * The canonical node for children {@code left}/{@code right} of a subtree of height {@code h}.
   */
  private static Node join(Node left, Node right, int h) {
    if (left == null && right == null) {
      return null;
    }
    if (left == null && right instanceof Single s) {
      return new Single(s.key(), s.leafHash(), h, node(EMPTY[h - 1], s.hash()));
    }
    if (right == null && left instanceof Single s) {
      return new Single(s.key(), s.leafHash(), h, node(s.hash(), EMPTY[h - 1]));
    }
    return new Branch(left, right, node(hashOf(left, h - 1), hashOf(right, h - 1)));
  }

  private static byte[] hashOf(Node n, int h) {
    return n == null ? EMPTY[h] : n.hash();
  }

  /** A single-key subtree of height {@code h}: {@code h} hashes up from the leaf. */
  private static Single single(long key, byte[] leafHash, int h) {
    byte[] cur = leafHash;
    for (int i = 0; i < h; i++) {
      cur = ((key >>> i) & 1) == 0 ? node(cur, EMPTY[i]) : node(EMPTY[i], cur);
    }
    return new Single(key, leafHash, h, cur);
  }
}
