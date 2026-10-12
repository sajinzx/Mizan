package com.sajin.mizan.chain;

import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.StateCodec;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.SnapshotStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Crash recovery (§6.7, M6-T5). Lives in {@code chain} rather than {@code store} because it decodes
 * blocks (ADR 0013).
 *
 * <ol>
 *   <li>The block log has already truncated a torn tail and refused mid-log corruption on open.
 *   <li>Every block is decoded and checked: height sequence, {@code prevHash} chain from the
 *       genesis anchor, {@code txRoot}.
 *   <li>The newest valid snapshot not beyond the log is loaded and its root verified. A snapshot
 *       newer than the log means committed blocks were lost, and recovery refuses.
 *   <li>Later blocks have their certificates verified, are replayed on the restored core, contain
 *       no {@code AUTH_FAILED} command, and match each header's {@code stateRoot}.
 * </ol>
 */
public final class Recovery {
  private Recovery() {}

  /** Commands and results of one replayed block (for rebuilding the gateway result cache). */
  public record Replayed(long height, List<SignedCommand> commands, List<ExecResult> results) {}

  /** The recovered state. Arrays are not copied. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Recovered(
      ExecutionCore committed,
      long height,
      byte[] headHash,
      long lastTimestampMillis,
      long snapshotHeight,
      List<Replayed> recent) {}

  public static Recovered recover(
      GenesisConfig g,
      SignatureVerifier verifier,
      BlockStore store,
      SnapshotStore snapshots,
      int keepRecent)
      throws PersistenceException {
    ValidatorSet validators = new ValidatorSet(g);
    int n = store.size();
    SnapshotStore.Snapshot newest = snapshots.latestValid(Long.MAX_VALUE);
    if (newest != null && newest.height() > n) {
      throw new PersistenceException(
          "snapshot at height "
              + newest.height()
              + " is beyond the block log ("
              + n
              + " blocks): committed blocks are missing; refusing to start");
    }
    ExecutionCore core;
    long snapHeight;
    if (newest == null) {
      core = ExecutionCore.genesis(g, verifier);
      snapHeight = 0;
    } else {
      try {
        core = StateCodec.decode(newest.state(), g, verifier);
      } catch (ProtocolException e) {
        throw new PersistenceException(
            "snapshot at height " + newest.height() + " does not decode", e);
      }
      if (!Arrays.equals(core.stateRoot(), newest.stateRoot())) {
        throw new PersistenceException(
            "snapshot at height " + newest.height() + " has a wrong state root");
      }
      snapHeight = newest.height();
    }

    byte[] prev = GenesisAnchor.hash(g);
    long lastTs = 0;
    ArrayDeque<Replayed> recent = new ArrayDeque<>();
    for (int i = 0; i < n; i++) {
      long height = i + 1L;
      CommittedBlock cb = readLinked(store, i, g, prev);
      BlockHeader h = cb.block().header();
      if (height == snapHeight && !Arrays.equals(h.stateRoot(), core.stateRoot())) {
        throw new PersistenceException("snapshot root differs from block " + height + " root");
      }
      if (height > snapHeight) {
        List<ExecResult> results = applyCertified(validators, verifier, core, cb);
        recent.addLast(new Replayed(height, cb.block().commands(), results));
        while (recent.size() > keepRecent) {
          recent.removeFirst();
        }
      }
      prev = cb.block().hash();
      lastTs = h.timestampMillis();
    }
    return new Recovered(core, n, prev, lastTs, snapHeight, List.copyOf(recent));
  }

  /**
   * Decodes record {@code i} (height {@code i + 1}) and checks its height, chain id, link to {@code
   * prev} and transaction root.
   */
  static CommittedBlock readLinked(BlockStore store, int i, GenesisConfig g, byte[] prev)
      throws PersistenceException {
    long height = i + 1L;
    CommittedBlock cb;
    try {
      cb = CommittedBlock.decode(store.read(i));
    } catch (ProtocolException e) {
      throw new PersistenceException("block " + height + " does not decode", e);
    }
    BlockHeader h = cb.block().header();
    if (h.height() != height || h.chainId() != g.chainId()) {
      throw new PersistenceException(
          "block " + height + " has height " + h.height() + " / chain " + h.chainId());
    }
    if (!Arrays.equals(h.prevHash(), prev)) {
      throw new PersistenceException("block " + height + " does not link to its predecessor");
    }
    if (!cb.block().txRootMatches()) {
      throw new PersistenceException("block " + height + " has a wrong txRoot");
    }
    return cb;
  }

  /**
   * Verifies the commit certificate of {@code cb}, replays its commands on {@code core}, and checks
   * the resulting state root against the header.
   */
  static List<ExecResult> applyCertified(
      ValidatorSet validators, SignatureVerifier verifier, ExecutionCore core, CommittedBlock cb)
      throws PersistenceException {
    long height = cb.height();
    BlockHeader h = cb.block().header();
    if (!validators.verifies(cb.cert(), height, cb.block().hash(), verifier)) {
      throw new PersistenceException("block " + height + " has an invalid commit certificate");
    }
    List<ExecResult> results = new ArrayList<>(cb.block().commands().size());
    for (SignedCommand c : cb.block().commands()) {
      ExecResult r = core.execute(c, ExecutionCore.SILENT);
      if (r.status() == ExecResult.Status.AUTH_FAILED) {
        throw new PersistenceException("block " + height + " contains an unauthenticated command");
      }
      results.add(r);
    }
    if (!Arrays.equals(core.stateRoot(), h.stateRoot())) {
      throw new PersistenceException(
          "replayed state root differs at height "
              + height
              + ": "
              + HexFormat.of().formatHex(core.stateRoot())
              + " vs header "
              + HexFormat.of().formatHex(h.stateRoot()));
    }
    return results;
  }
}
