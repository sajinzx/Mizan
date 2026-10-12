package com.sajin.mizan.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** M10-T2: the sparse Merkle tree against its definition, persistence, and batching. */
class SparseMerkleTreeTest {

  /** The root straight from the definition: recurse over all 64 levels (test-sized inputs only). */
  static byte[] reference(Map<Long, byte[]> leafHashes) {
    return subtree(new ArrayList<>(leafHashes.entrySet()), SparseMerkleTree.DEPTH);
  }

  private static byte[] subtree(List<Map.Entry<Long, byte[]>> entries, int h) {
    if (entries.isEmpty()) {
      return SparseMerkleTree.emptyHash(h);
    }
    if (h == 0) {
      return entries.get(0).getValue();
    }
    List<Map.Entry<Long, byte[]>> left = new ArrayList<>();
    List<Map.Entry<Long, byte[]>> right = new ArrayList<>();
    for (Map.Entry<Long, byte[]> e : entries) {
      (((e.getKey() >>> (h - 1)) & 1) == 0 ? left : right).add(e);
    }
    return SparseMerkleTree.node(subtree(left, h - 1), subtree(right, h - 1));
  }

  static long randomKey(SplittableRandom rnd) {
    return switch (rnd.nextInt(4)) {
      case 0 -> rnd.nextLong(); // anywhere, including negative (top bit set)
      case 1 -> rnd.nextLong(64); // dense small keys: long shared paths
      case 2 -> (rnd.nextLong(1 << 20) << 16) | rnd.nextInt(4); // ledger-style account<<16|asset
      default -> Long.MIN_VALUE + rnd.nextInt(8); // keys that differ only in low bits, top set
    };
  }

  static SparseMerkleTree apply(SparseMerkleTree t, Map<Long, byte[]> batch) {
    long[] keys = new long[batch.size()];
    int i = 0;
    for (long k : batch.keySet()) {
      keys[i++] = k;
    }
    keys = SparseMerkleTree.sortUnique(keys, keys.length);
    byte[][] hashes = new byte[keys.length][];
    for (int j = 0; j < keys.length; j++) {
      hashes[j] = batch.get(keys[j]);
    }
    return t.update(keys, hashes);
  }

  @Property(tries = 300)
  void matchesTheDefinitionUnderRandomBatches(
      @ForAll long seed, @ForAll @IntRange(min = 1, max = 12) int rounds) {
    SplittableRandom rnd = new SplittableRandom(seed);
    SparseMerkleTree t = SparseMerkleTree.EMPTY_TREE;
    Map<Long, byte[]> model = new TreeMap<>();
    List<Long> known = new ArrayList<>();
    for (int r = 0; r < rounds; r++) {
      Map<Long, byte[]> batch = new TreeMap<>();
      int n = 1 + rnd.nextInt(20);
      for (int i = 0; i < n; i++) {
        long k =
            !known.isEmpty() && rnd.nextInt(3) == 0
                ? known.get(rnd.nextInt(known.size()))
                : randomKey(rnd);
        byte[] v =
            rnd.nextInt(4) == 0
                ? null
                : SparseMerkleTree.leafHash(k, new byte[] {(byte) rnd.nextInt(), (byte) r});
        batch.put(k, v);
        known.add(k);
      }
      SparseMerkleTree before = t;
      byte[] beforeRoot = before.root();
      t = apply(t, batch);
      batch.forEach(
          (k, v) -> {
            if (v == null) {
              model.remove(k);
            } else {
              model.put(k, v);
            }
          });
      assertThat(t.root()).isEqualTo(reference(model));
      assertThat(before.root())
          .as("persistent: the old version is unchanged")
          .isEqualTo(beforeRoot);
    }
  }

  @Test
  void batchEqualsOneByOneAndOrderIsIrrelevant() {
    SplittableRandom rnd = new SplittableRandom(3);
    Map<Long, byte[]> all = new TreeMap<>();
    for (int i = 0; i < 500; i++) {
      long k = randomKey(rnd);
      all.put(k, SparseMerkleTree.leafHash(k, new byte[] {(byte) i}));
    }
    SparseMerkleTree batched = apply(SparseMerkleTree.EMPTY_TREE, all);
    SparseMerkleTree single = SparseMerkleTree.EMPTY_TREE;
    List<Long> keys = new ArrayList<>(all.keySet());
    java.util.Collections.shuffle(keys, new java.util.Random(9));
    for (long k : keys) {
      single = single.update(new long[] {k}, new byte[][] {all.get(k)});
    }
    assertThat(batched.root()).isEqualTo(single.root()).isEqualTo(reference(all));
  }

  @Test
  void emptyAndRemovals() {
    assertThat(SparseMerkleTree.EMPTY_TREE.root()).isEqualTo(SparseMerkleTree.emptyHash(64));
    byte[] h5 = SparseMerkleTree.leafHash(5, new byte[] {1});
    SparseMerkleTree one = SparseMerkleTree.EMPTY_TREE.update(new long[] {5}, new byte[][] {h5});
    assertThat(one.root()).isEqualTo(reference(Map.of(5L, h5)));
    assertThat(one.update(new long[] {5}, new byte[][] {null}).root())
        .isEqualTo(SparseMerkleTree.emptyHash(64));
    assertThat(one.update(new long[] {6}, new byte[][] {null}).root())
        .as("removing an absent key changes nothing")
        .isEqualTo(one.root());
    assertThat(SparseMerkleTree.emptyHash(1))
        .isEqualTo(SparseMerkleTree.node(new byte[32], new byte[32]));
    assertThatThrownBy(() -> one.update(new long[] {2, 1}, new byte[2][]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(SparseMerkleTree.sortUnique(new long[] {-1, 3, 3, 0, Long.MIN_VALUE}, 5))
        .containsExactly(0, 3, Long.MIN_VALUE, -1);
  }
}
