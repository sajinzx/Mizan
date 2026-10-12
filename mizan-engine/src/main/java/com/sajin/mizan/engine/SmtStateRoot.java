package com.sajin.mizan.engine;

import com.sajin.mizan.crypto.Hashing;
import com.sajin.mizan.crypto.SparseMerkleTree;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * State root, version 2 (§5.6, M10-T2): the same leaves as {@link SimpleStateRoot}, in three {@link
 * SparseMerkleTree}s that are updated incrementally.
 *
 * <pre>
 * stateRoot = SHA256(0x03 || ledgerRoot || metaRoot || bookRoot || nextTradeSeq:8 || nextOrderId:8)
 * </pre>
 *
 * <p>The ledger and the books report every key they change ({@link StateDirt}); {@link #root}
 * recomputes only those leaves from the current state and applies them to the trees in one batch
 * per tree. The tag differs from version 1 ({@code 0x02}), so a v1 and a v2 root can never be
 * confused.
 */
final class SmtStateRoot implements StateDirt {
  private static final byte ROOT_TAG = 0x03;

  /** A growable list of dirty keys (sorted and de-duplicated at flush; never hash-iterated). */
  private static final class Keys {
    long[] a = new long[64];
    int n;

    void add(long k) {
      if (n == a.length) {
        a = Arrays.copyOf(a, Math.multiplyExact(n, 2));
      }
      a[n++] = k;
    }

    Keys copy() {
      Keys c = new Keys();
      c.a = Arrays.copyOf(a, Math.max(64, n));
      c.n = n;
      return c;
    }
  }

  private SparseMerkleTree ledgerTree;
  private SparseMerkleTree metaTree;
  private SparseMerkleTree bookTree;
  private Keys dirtyLedger = new Keys();
  private Keys dirtyMeta = new Keys();
  private Keys dirtyBook = new Keys();

  private SmtStateRoot(SparseMerkleTree ledger, SparseMerkleTree meta, SparseMerkleTree book) {
    this.ledgerTree = ledger;
    this.metaTree = meta;
    this.bookTree = book;
  }

  /** Builds the trees from all current state of {@code core} (genesis, snapshots, oracle). */
  static SmtStateRoot fromScratch(ExecutionCore core) {
    SmtStateRoot s =
        new SmtStateRoot(
            SparseMerkleTree.EMPTY_TREE, SparseMerkleTree.EMPTY_TREE, SparseMerkleTree.EMPTY_TREE);
    core.ledger()
        .forEachBalance((account, asset, free, held) -> s.ledger(Ledger.key(account, asset)));
    core.ledger().forEachAccount((account, nonce, pk) -> s.meta(account));
    for (OrderBook b : core.books()) {
      for (Order o : b.orders()) {
        s.order(o.id());
      }
    }
    s.flush(core);
    return s;
  }

  /** O(1) apart from the pending dirty keys: the trees are persistent and shared. */
  SmtStateRoot copy() {
    SmtStateRoot c = new SmtStateRoot(ledgerTree, metaTree, bookTree);
    c.dirtyLedger = dirtyLedger.copy();
    c.dirtyMeta = dirtyMeta.copy();
    c.dirtyBook = dirtyBook.copy();
    return c;
  }

  @Override
  public void ledger(long key) {
    dirtyLedger.add(key);
  }

  @Override
  public void meta(long account) {
    dirtyMeta.add(account);
  }

  @Override
  public void order(long orderId) {
    dirtyBook.add(orderId);
  }

  byte[] root(ExecutionCore core) {
    flush(core);
    return Hashing.sha256(
        new byte[] {ROOT_TAG},
        ledgerTree.root(),
        metaTree.root(),
        bookTree.root(),
        ByteBuffer.allocate(16).putLong(core.nextTradeSeq()).putLong(core.nextOrderId()).array());
  }

  private void flush(ExecutionCore core) {
    Ledger l = core.ledger();
    if (dirtyLedger.n > 0) {
      long[] keys = SparseMerkleTree.sortUnique(dirtyLedger.a, dirtyLedger.n);
      byte[][] leaves = new byte[keys.length][];
      for (int i = 0; i < keys.length; i++) {
        long account = keys[i] >>> 16;
        int asset = (int) (keys[i] & 0xFFFF);
        long free = l.free(account, asset);
        long held = l.held(account, asset);
        leaves[i] =
            free == 0 && held == 0
                ? null
                : SparseMerkleTree.leafHash(
                    keys[i], ByteBuffer.allocate(16).putLong(free).putLong(held).array());
      }
      ledgerTree = ledgerTree.update(keys, leaves);
      dirtyLedger.n = 0;
    }
    if (dirtyMeta.n > 0) {
      long[] keys = SparseMerkleTree.sortUnique(dirtyMeta.a, dirtyMeta.n);
      byte[][] leaves = new byte[keys.length][];
      for (int i = 0; i < keys.length; i++) {
        leaves[i] =
            l.isRegistered(keys[i])
                ? SparseMerkleTree.leafHash(
                    keys[i],
                    ByteBuffer.allocate(8 + 32)
                        .putLong(l.lastNonce(keys[i]))
                        .put(Hashing.sha256(l.pubKey(keys[i])))
                        .array())
                : null;
      }
      metaTree = metaTree.update(keys, leaves);
      dirtyMeta.n = 0;
    }
    if (dirtyBook.n > 0) {
      long[] keys = SparseMerkleTree.sortUnique(dirtyBook.a, dirtyBook.n);
      byte[][] leaves = new byte[keys.length][];
      for (int i = 0; i < keys.length; i++) {
        Order o = core.findOrder(keys[i]);
        leaves[i] =
            o == null ? null : SparseMerkleTree.leafHash(keys[i], SimpleStateRoot.bookValue(o));
      }
      bookTree = bookTree.update(keys, leaves);
      dirtyBook.n = 0;
    }
  }
}
