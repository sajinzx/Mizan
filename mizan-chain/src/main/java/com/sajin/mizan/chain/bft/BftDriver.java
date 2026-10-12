package com.sajin.mizan.chain.bft;

import com.sajin.mizan.chain.Block;
import com.sajin.mizan.chain.BlockHeader;
import com.sajin.mizan.chain.BlockValidator;
import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.chain.Recovery;
import com.sajin.mizan.chain.ValidatorSet;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import com.sajin.mizan.engine.StateCodec;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.SnapshotStore;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link RoundBft} for one validator over a real clock (M7). Everything happens on one
 * single-threaded scheduled executor (the {@code consensus} thread): network messages, timeouts,
 * drafts from the sequencer, decisions. That thread also owns the committed core and the block log.
 *
 * <p>As the application of {@link RoundBft}:
 *
 * <ul>
 *   <li><b>propose</b> (proposer only): the current sequencer draft as a block, or, when there is
 *       no draft for {@code emptyBlockMs}, an empty block so that height keeps moving;
 *   <li><b>valid</b>: {@link BlockValidator} in live mode, replaying on a copy of the committed
 *       core; the replayed copy is cached for the decision;
 *   <li><b>decide</b>: append + fsync, adopt the replayed core, snapshot, and if the block is the
 *       pending draft, {@code Committed} to the sequencer; then {@code FINAL}.
 * </ul>
 *
 * <p>BFT mode requires {@code maxInflight = 1}: at most one draft is waiting, so its state root is
 * always relative to the committed state (empty blocks do not change state).
 */
public final class BftDriver implements Consumer<Sequencer.BlockDraft>, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(BftDriver.class);

  /** Static configuration. */
  public record Config(
      GenesisConfig genesis,
      int me,
      MessageSigner signer,
      SignatureVerifier verifier,
      RoundBft.ProposerFn proposer,
      SafetyStore safety,
      int snapshotEvery,
      long emptyBlockMs,
      RoundBft.Observer observer) {}

  private final Config cfg;
  private final ValidatorSet vs;
  private final BlockStore store;
  private final SnapshotStore snapshots;
  private final FinalityListener finality;
  private final ScheduledExecutorService loop;
  private final ArrayDeque<Sequencer.BlockDraft> drafts = new ArrayDeque<>();
  private final Map<ByteBuffer, BlockValidator.Valid> validated = new HashMap<>();
  private volatile Throwable failure;
  private Network network;
  private Sequencer sequencer;
  private RoundBft bft;

  // ---- owned by the consensus thread ----
  private ExecutionCore committed;
  private volatile long height;
  private byte[] head;
  private long headTimestamp;
  private long lastDecidedAt;
  private Sequencer.BlockDraft current;
  private long epoch;

  public BftDriver(
      Config cfg,
      Recovery.Recovered from,
      BlockStore store,
      SnapshotStore snapshots,
      FinalityListener finality) {
    this.cfg = cfg;
    this.vs = new ValidatorSet(cfg.genesis());
    this.store = store;
    this.snapshots = snapshots;
    this.finality = finality;
    this.committed = from.committed();
    this.height = from.height();
    this.head = from.headHash().clone();
    this.headTimestamp = from.lastTimestampMillis();
    this.loop =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().name("consensus").daemon(true).unstarted(r));
  }

  /** Delivers a message from the network (any thread). */
  public void onNetworkMessage(int from, byte[] msg) {
    run(() -> bft.onMessage(from, msg));
  }

  /**
   * Starts consensus. {@code sequencer} is {@code null} on validators without a sequencer (they
   * never propose in M7).
   */
  public void start(Network net, Sequencer seq) {
    this.network = net;
    this.sequencer = seq;
    this.lastDecidedAt = System.currentTimeMillis();
    run(
        () -> {
          bft =
              new RoundBft(
                  new RoundBft.Config(
                      cfg.me(),
                      vs,
                      cfg.signer(),
                      cfg.verifier(),
                      network,
                      scheduler(),
                      app(),
                      cfg.safety(),
                      cfg.proposer(),
                      cfg.genesis().consensus().proposeTimeoutMs(),
                      cfg.genesis().consensus().timeoutDeltaMs(),
                      cfg.observer()),
                  height + 1,
                  head,
                  headTimestamp);
          bft.start();
        });
  }

  /** A sequencer draft (sequencer thread). */
  @Override
  public void accept(Sequencer.BlockDraft d) {
    run(
        () -> {
          if (d.epoch() >= epoch) {
            drafts.addLast(d);
            bft.valueReady();
          }
        });
  }

  public long height() {
    return height;
  }

  public Throwable failure() {
    return failure;
  }

  /** The committed core; only safe after {@link #close()}. */
  public ExecutionCore committedCore() {
    return committed;
  }

  private void run(Runnable task) {
    try {
      loop.execute(
          () -> {
            if (failure != null) {
              return;
            }
            try {
              task.run();
            } catch (RuntimeException | Error e) {
              failure = e;
              LOG.error("consensus halted", e);
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // shutting down
    }
  }

  private Scheduler scheduler() {
    return new Scheduler() {
      @Override
      public long nowMillis() {
        return System.currentTimeMillis();
      }

      @Override
      public Cancellable schedule(long delayMillis, Runnable task) {
        boolean[] cancelled = {false};
        try {
          var unused =
              loop.schedule(
                  () -> {
                    if (!cancelled[0] && failure == null) {
                      try {
                        task.run();
                      } catch (RuntimeException | Error e) {
                        failure = e;
                        LOG.error("consensus halted", e);
                      }
                    }
                  },
                  delayMillis,
                  TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
          // shutting down
        }
        return () -> cancelled[0] = true;
      }
    };
  }

  private BlockValidator.Context context() {
    return new BlockValidator.Context(
        vs,
        height + 1,
        head,
        headTimestamp,
        (h, r) -> cfg.proposer().proposer(h, (int) r),
        cfg.genesis().block().maxCommands());
  }

  private RoundBft.App app() {
    return new RoundBft.App() {
      @Override
      public Block propose(long h, int round, byte[] prevHash, long prevTs) {
        if (current == null) {
          current = drafts.pollFirst();
        }
        long now = System.currentTimeMillis();
        List<com.sajin.mizan.core.SignedCommand> cmds;
        byte[] root;
        if (current != null) {
          cmds = current.commands();
          root = current.stateRoot();
        } else if (now - lastDecidedAt >= cfg.emptyBlockMs()) {
          cmds = List.of();
          root = committed.stateRoot();
        } else {
          scheduler().schedule(cfg.emptyBlockMs() - (now - lastDecidedAt), () -> bft.valueReady());
          return null;
        }
        long ts = Math.max(prevTs + 1, now);
        return new Block(
            new BlockHeader(
                BlockHeader.VERSION,
                vs.chainId(),
                h,
                round,
                prevHash,
                Block.txRoot(cmds),
                root,
                cfg.me(),
                ts),
            cmds);
      }

      @Override
      public boolean valid(Block b) {
        return remember(
            b,
            BlockValidator.validate(
                b,
                null,
                committed,
                context(),
                BlockValidator.Mode.LIVE,
                System.currentTimeMillis(),
                cfg.verifier()));
      }

      @Override
      public boolean validCommitted(CommittedBlock cb) {
        return remember(
            cb.block(),
            BlockValidator.validate(
                cb.block(),
                cb.cert(),
                committed,
                context(),
                BlockValidator.Mode.SYNC,
                System.currentTimeMillis(),
                cfg.verifier()));
      }

      @Override
      public void decide(CommittedBlock cb) {
        commit(cb);
      }

      @Override
      public CommittedBlock committed(long h) {
        if (h < 1 || h > store.size()) {
          return null;
        }
        try {
          return CommittedBlock.decode(store.read((int) h - 1));
        } catch (PersistenceException | ProtocolException e) {
          return null;
        }
      }
    };
  }

  private boolean remember(Block b, BlockValidator.Result r) {
    if (r instanceof BlockValidator.Valid v) {
      validated.put(ByteBuffer.wrap(b.hash()), v);
      return true;
    }
    LOG.warn(
        "validator {}: invalid block at height {}: {}",
        cfg.me(),
        b.height(),
        ((BlockValidator.Invalid) r).reason());
    return false;
  }

  private void commit(CommittedBlock cb) {
    BlockValidator.Valid v = validated.get(ByteBuffer.wrap(cb.block().hash()));
    if (v == null) {
      throw new InvariantViolationError(
          "decided a block that was never validated at " + cb.height());
    }
    try {
      store.append(cb.encode());
      committed = v.next();
      height = cb.height();
      head = cb.block().hash();
      headTimestamp = cb.block().header().timestampMillis();
      lastDecidedAt = System.currentTimeMillis();
      validated.clear();
      if (height % cfg.snapshotEvery() == 0) {
        snapshots.write(height, committed.stateRoot(), StateCodec.encode(committed));
      }
    } catch (PersistenceException e) {
      throw new IllegalStateException("cannot persist block " + cb.height(), e);
    }
    boolean ownDraft =
        current != null
            && !cb.block().commands().isEmpty()
            && Arrays.equals(cb.block().header().txRoot(), Block.txRoot(current.commands()));
    if (ownDraft) {
      current = null;
      while (!sequencer.control(new Sequencer.Committed(height))) {
        Thread.onSpinWait();
      }
    } else if (current != null && !cb.block().commands().isEmpty()) {
      // Someone else's non-empty block landed first: our draft is stale.
      epoch++;
      current = null;
      drafts.clear();
      while (!sequencer.control(new Sequencer.Rollback(committed.copy(), List.of()))) {
        Thread.onSpinWait();
      }
    }
    List<ExecResult> results = v.results();
    if (!cb.block().commands().isEmpty()) {
      LOG.info(
          "validator {}: committed height {} round {} with {} commands",
          cfg.me(),
          height,
          cb.cert().round(),
          cb.block().commands().size());
    }
    finality.onFinal(height, cb.block().commands(), results);
  }

  @Override
  public void close() {
    loop.shutdownNow();
    try {
      loop.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
