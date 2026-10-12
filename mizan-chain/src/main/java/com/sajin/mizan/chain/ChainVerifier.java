package com.sajin.mizan.chain;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.StateCodec;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.SnapshotStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Re-verifies a data directory from genesis (M9-T4, {@code mizan verify-chain}): every block must
 * decode, link to its predecessor, carry a valid commit certificate, and replay from genesis to the
 * state root in its header. Every valid snapshot must decode to the root of the replayed chain at
 * its height. Nothing is trusted from snapshots, and nothing on disk is modified.
 */
public final class ChainVerifier {
  private ChainVerifier() {}

  /** What was verified. Arrays are not copied. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Report(
      long height,
      byte[] headHash,
      byte[] stateRoot,
      long commands,
      long tornBytes,
      int snapshotsChecked) {}

  /**
   * Verifies {@code store} and {@code snapshots} from genesis.
   *
   * @throws PersistenceException naming the first height (or snapshot) that fails verification
   */
  public static Report verify(
      GenesisConfig g,
      SignatureVerifier verifier,
      BlockStore store,
      List<SnapshotStore.Snapshot> snapshots)
      throws PersistenceException {
    ValidatorSet vs = new ValidatorSet(g);
    Map<Long, SnapshotStore.Snapshot> byHeight = new TreeMap<>();
    for (SnapshotStore.Snapshot s : snapshots) {
      if (s.height() > store.size()) {
        throw new PersistenceException(
            "snapshot at height " + s.height() + " is beyond the block log (" + store.size() + ")");
      }
      byHeight.put(s.height(), s);
    }
    ExecutionCore core = ExecutionCore.genesis(g, verifier);
    byte[] prev = GenesisAnchor.hash(g);
    long commands = 0;
    int checked = 0;
    for (int i = 0; i < store.size(); i++) {
      CommittedBlock cb = Recovery.readLinked(store, i, g, prev);
      commands += Recovery.applyCertified(vs, verifier, core, cb).size();
      SnapshotStore.Snapshot snap = byHeight.get(cb.height());
      if (snap != null) {
        checkSnapshot(g, verifier, snap, core.stateRoot());
        checked++;
      }
      prev = cb.block().hash();
    }
    return new Report(
        store.size(), prev, core.stateRoot(), commands, store.truncatedBytes(), checked);
  }

  private static void checkSnapshot(
      GenesisConfig g, SignatureVerifier verifier, SnapshotStore.Snapshot s, byte[] chainRoot)
      throws PersistenceException {
    if (!Arrays.equals(s.stateRoot(), chainRoot)) {
      throw new PersistenceException(
          "snapshot at height " + s.height() + " records a root that differs from the chain");
    }
    try {
      if (!Arrays.equals(StateCodec.decode(s.state(), g, verifier).stateRoot(), chainRoot)) {
        throw new PersistenceException(
            "snapshot at height " + s.height() + " decodes to a different state");
      }
    } catch (ProtocolException e) {
      throw new PersistenceException("snapshot at height " + s.height() + " does not decode", e);
    }
  }
}
