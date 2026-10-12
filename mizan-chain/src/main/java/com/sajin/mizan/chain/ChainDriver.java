package com.sajin.mizan.chain;

import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import com.sajin.mizan.engine.StateCodec;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.FaultPoints;
import com.sajin.mizan.store.SnapshotStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The commit path (M6-T7) on the dedicated {@code consensus} thread, which owns the committed core:
 *
 * <ol>
 *   <li>take the next draft of the current epoch (stale drafts from before a rollback are skipped);
 *   <li>run consensus to get a block and its certificate;
 *   <li>validate by replaying the block on a <em>copy</em> of the committed core: no {@code
 *       AUTH_FAILED}, and the root equals the header's;
 *   <li>append the committed block to the log and fsync — from here on it is durable;
 *   <li>adopt the replayed copy as the committed core; snapshot every {@code snapshotEvery} blocks;
 *   <li>tell the sequencer ({@code Committed}) and the finality listener ({@code FINAL}).
 * </ol>
 *
 * <p>If consensus fails, or the test hook rejects a draft (M6-T8), the driver bumps its epoch and
 * sends the sequencer a {@code Rollback} with a fresh copy of the committed core. The sequencer
 * revokes from the oldest uncommitted trade and replays everything that was not committed.
 */
public final class ChainDriver implements Consumer<Sequencer.BlockDraft>, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ChainDriver.class);

  private final ConsensusEngine consensus;
  private final BlockStore store;
  private final SnapshotStore snapshots;
  private final int snapshotEvery;
  private final FinalityListener finality;
  private final Predicate<Sequencer.BlockDraft> failHook;
  private final LinkedBlockingQueue<Sequencer.BlockDraft> drafts = new LinkedBlockingQueue<>();
  private final Thread thread;
  private volatile boolean running = true;
  private volatile Sequencer sequencer;
  private volatile Throwable failure;

  // ---- owned by the consensus thread ----
  private ExecutionCore committed;
  private volatile long height;
  private volatile ChainHead published;
  private byte[] head;
  private long lastTimestamp;
  private long epoch;

  public ChainDriver(
      Recovery.Recovered from,
      ConsensusEngine consensus,
      BlockStore store,
      SnapshotStore snapshots,
      int snapshotEvery,
      FinalityListener finality,
      Predicate<Sequencer.BlockDraft> failHook) {
    this.committed = from.committed();
    this.height = from.height();
    this.head = from.headHash().clone();
    this.lastTimestamp = from.lastTimestampMillis();
    this.published = new ChainHead(height, head, from.committed().stateRoot());
    this.consensus = consensus;
    this.store = store;
    this.snapshots = snapshots;
    this.snapshotEvery = Math.max(1, snapshotEvery);
    this.finality = finality;
    this.failHook = failHook == null ? d -> false : failHook;
    this.thread = Thread.ofPlatform().name("consensus").daemon(true).unstarted(this::loop);
  }

  public void start(Sequencer seq) {
    this.sequencer = seq;
    thread.start();
  }

  /** Called on the sequencer thread; never blocks. */
  @Override
  public void accept(Sequencer.BlockDraft d) {
    drafts.add(d);
  }

  public long height() {
    return height;
  }

  /** The committed head (any thread). */
  public ChainHead head() {
    return published;
  }

  /** Non-null if the consensus thread died (the node must halt). */
  public Throwable failure() {
    return failure;
  }

  /** The committed core; only safe to read after {@link #close()}. */
  public ExecutionCore committedCore() {
    return committed;
  }

  private void loop() {
    try {
      while (running) {
        Sequencer.BlockDraft d = drafts.take();
        if (d.epoch() < 0) { // STOP
          return;
        }
        if (d.epoch() < epoch) {
          continue; // cut before the last rollback; its commands are being replayed
        }
        if (failHook.test(d)) {
          rollback("draft rejected by hook");
          continue;
        }
        CommittedBlock cb;
        try {
          cb = consensus.propose(d, height + 1, head, lastTimestamp).get();
        } catch (ExecutionException e) {
          rollback("consensus failed: " + e.getCause());
          continue;
        }
        commit(cb);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (PersistenceException | RuntimeException | Error e) {
      failure = e;
      LOG.atError().setMessage("consensus_halted").addKeyValue("height", height).setCause(e).log();
    }
  }

  private void commit(CommittedBlock cb) throws PersistenceException {
    List<SignedCommand> cmds = cb.block().commands();
    ExecutionCore next = committed.copy();
    List<ExecResult> results = new ArrayList<>(cmds.size());
    for (int i = 0; i < cmds.size(); i++) {
      if (i == cmds.size() / 2) {
        FaultPoints.hit(FaultPoints.MID_BLOCK);
      }
      ExecResult r = next.execute(cmds.get(i), ExecutionCore.SILENT);
      if (r.status() == ExecResult.Status.AUTH_FAILED) {
        throw new InvariantViolationError(
            "own draft contains an unauthenticated command at " + cb.height());
      }
      results.add(r);
    }
    if (!Arrays.equals(next.stateRoot(), cb.block().header().stateRoot())) {
      throw new InvariantViolationError("replayed root differs from block root at " + cb.height());
    }
    store.append(cb.encode()); // durable from here
    committed = next;
    height = cb.height();
    head = cb.block().hash();
    lastTimestamp = cb.block().header().timestampMillis();
    published = new ChainHead(height, head, cb.block().header().stateRoot());
    if (height % snapshotEvery == 0) {
      FaultPoints.hit(FaultPoints.AFTER_APPEND);
      snapshots.write(height, committed.stateRoot(), StateCodec.encode(committed));
    }
    while (!sequencer.control(new Sequencer.Committed(height))) {
      Thread.onSpinWait();
    }
    finality.onFinal(height, cmds, results);
  }

  private void rollback(String why) {
    epoch++;
    LOG.atWarn()
        .setMessage("rollback")
        .addKeyValue("height", height)
        .addKeyValue("reason", why)
        .log();
    while (!sequencer.control(new Sequencer.Rollback(committed.copy(), List.of()))) {
      Thread.onSpinWait();
    }
  }

  /** Poison pill: lets an in-flight commit (append + fsync) finish before the thread exits. */
  private static final Sequencer.BlockDraft STOP =
      new Sequencer.BlockDraft(-1, 0, List.of(), new byte[32]);

  @Override
  public void close() {
    running = false;
    drafts.add(STOP);
    try {
      thread.join(5_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
