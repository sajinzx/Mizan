package com.sajin.mizan.chain.bft;

import com.sajin.mizan.chain.Block;
import com.sajin.mizan.chain.BlockHeader;
import com.sajin.mizan.chain.BlockValidator;
import com.sajin.mizan.chain.CommitCert;
import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.chain.Recovery;
import com.sajin.mizan.chain.ValidatorSet;
import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.ResultListener;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import com.sajin.mizan.engine.StateCodec;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.SnapshotStore;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

  /**
   * Rotation mode (M8): blocks come from the command pool rather than sequencer drafts. Provisional
   * results of this validator's own proposals go to {@code provisional}; the engine events of
   * decided blocks go to {@code committedEvents}, followed by {@code REVOKE} when this validator's
   * own proposal lost.
   */
  public record Rotation(
      CommandPool pool,
      ResultListener provisional,
      EngineListener committedEvents,
      long gossipIntervalMs) {}

  private final Config cfg;
  private final Rotation rotation;
  private final Map<ByteBuffer, RecordedEvents> events = new HashMap<>();
  private byte[] myProposalHash;
  private long myProposalFirstTradeSeq = -1;
  private final ValidatorSet vs;
  private final BlockStore store;
  private final SnapshotStore snapshots;
  private final FinalityListener finality;
  private final ScheduledExecutorService loop;
  private final ArrayDeque<Sequencer.BlockDraft> drafts = new ArrayDeque<>();
  private final Map<ByteBuffer, BlockValidator.Valid> validated = new HashMap<>();
  private volatile Throwable failure;
  private volatile boolean closing;
  private Network network;
  private Sequencer sequencer;
  private RoundBft bft;

  // ---- owned by the consensus thread ----
  private ExecutionCore committed;
  private volatile long height;
  private volatile byte[] head;
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
    this(cfg, from, store, snapshots, finality, null);
  }

  public BftDriver(
      Config cfg,
      Recovery.Recovered from,
      BlockStore store,
      SnapshotStore snapshots,
      FinalityListener finality,
      Rotation rotation) {
    this.cfg = cfg;
    this.rotation = rotation;
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
          if (rotation != null) {
            scheduleGossip();
          }
        });
  }

  /** New commands may be in the pool: let a waiting proposer propose (any thread). */
  public void poke() {
    run(() -> bft.valueReady());
  }

  /** A peer's height from heartbeats (any thread): sync if it is ahead. */
  public void onPeerHeight(int peer, long peerHeight) {
    run(() -> bft.onPeerHeight(peer, peerHeight));
  }

  /** Gossips a locally received command to every validator (any thread). */
  public void gossip(SignedCommand c) {
    network.broadcast(ConsensusMessage.encode(new ConsensusMessage.Tx(cfg.me(), c.raw())));
  }

  private void scheduleGossip() {
    scheduler()
        .schedule(
            rotation.gossipIntervalMs(),
            () -> {
              long now = System.currentTimeMillis();
              for (SignedCommand c :
                  rotation.pool().dueForGossip(now, 2 * rotation.gossipIntervalMs(), 256)) {
                gossip(c);
              }
              scheduleGossip();
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

  /** Hash of the latest committed block (the genesis anchor at height 0). */
  public byte[] headHash() {
    return head.clone();
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
            if (failure != null || closing) {
              return;
            }
            try {
              task.run();
            } catch (RuntimeException | Error e) {
              failure = e;
              LOG.atError()
                  .setMessage("consensus_halted")
                  .addKeyValue("validator", cfg.me())
                  .addKeyValue("height", height)
                  .setCause(e)
                  .log();
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
                    if (!cancelled[0] && failure == null && !closing) {
                      try {
                        task.run();
                      } catch (RuntimeException | Error e) {
                        failure = e;
                        LOG.atError()
                            .setMessage("consensus_halted")
                            .addKeyValue("validator", cfg.me())
                            .addKeyValue("height", height)
                            .setCause(e)
                            .log();
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
        if (rotation != null) {
          return proposeFromPool(h, round, prevHash, prevTs);
        }
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
        return check(b, null, BlockValidator.Mode.LIVE);
      }

      @Override
      public boolean validCommitted(CommittedBlock cb) {
        return check(cb.block(), cb.cert(), BlockValidator.Mode.SYNC);
      }

      @Override
      public void onTx(int from, byte[] envelope) {
        if (rotation == null) {
          return;
        }
        try {
          SignedCommand c = CommandCodec.decode(envelope);
          if (rotation.pool().add(c, false, System.currentTimeMillis()) == CommandPool.Added.NEW) {
            bft.valueReady();
          }
        } catch (ProtocolException e) {
          // malformed gossip: ignore
        }
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

  /** Validates {@code b}; keeps the replayed core and its engine events for the decision. */
  private boolean check(Block b, CommitCert cert, BlockValidator.Mode mode) {
    ByteBuffer key = ByteBuffer.wrap(b.hash());
    if (validated.containsKey(key)) {
      return true; // e.g. our own proposal, already executed
    }
    RecordedEvents ev = new RecordedEvents();
    BlockValidator.Result r =
        BlockValidator.validate(
            b, cert, committed, context(), mode, System.currentTimeMillis(), cfg.verifier(), ev);
    if (remember(b, r)) {
      events.put(key, ev);
      return true;
    }
    return false;
  }

  /**
   * Rotation-mode proposal: execute the head of the pool on a copy of the committed core (the only
   * optimistic execution in this mode), skip commands that fail authentication (defect 1), and
   * report provisional results to local clients.
   */
  private Block proposeFromPool(long h, int round, byte[] prevHash, long prevTs) {
    long now = System.currentTimeMillis();
    int max = cfg.genesis().block().maxCommands();
    List<SignedCommand> candidates = rotation.pool().peek(4 * max);
    ExecutionCore next = committed.copy();
    long firstTradeSeq = next.nextTradeSeq();
    RecordedEvents ev = new RecordedEvents();
    List<SignedCommand> included = new ArrayList<>();
    List<ExecResult> results = new ArrayList<>();
    List<SignedCommand> unauthenticated = new ArrayList<>();
    for (SignedCommand c : candidates) {
      if (included.size() == max) {
        break;
      }
      ExecResult r = next.execute(c, ev);
      if (r.status() == ExecResult.Status.AUTH_FAILED) {
        unauthenticated.add(c);
      } else {
        included.add(c);
        results.add(r);
      }
    }
    rotation.pool().remove(unauthenticated);
    if (included.isEmpty() && now - lastDecidedAt < cfg.emptyBlockMs()) {
      scheduler().schedule(cfg.emptyBlockMs() - (now - lastDecidedAt), () -> bft.valueReady());
      return null;
    }
    long ts = Math.max(prevTs + 1, now);
    Block b =
        new Block(
            new BlockHeader(
                BlockHeader.VERSION,
                vs.chainId(),
                h,
                round,
                prevHash,
                Block.txRoot(included),
                next.stateRoot(),
                cfg.me(),
                ts),
            included);
    ByteBuffer key = ByteBuffer.wrap(b.hash());
    validated.put(key, new BlockValidator.Valid(next, results));
    events.put(key, ev);
    myProposalHash = b.hash();
    myProposalFirstTradeSeq = firstTradeSeq;
    for (int i = 0; i < included.size(); i++) {
      rotation.provisional().onResult(included.get(i), results.get(i));
    }
    return b;
  }

  private boolean remember(Block b, BlockValidator.Result r) {
    if (r instanceof BlockValidator.Valid v) {
      validated.put(ByteBuffer.wrap(b.hash()), v);
      return true;
    }
    LOG.atWarn()
        .setMessage("invalid_block")
        .addKeyValue("validator", cfg.me())
        .addKeyValue("height", b.height())
        .addKeyValue("proposer", b.header().proposer())
        .addKeyValue("reason", ((BlockValidator.Invalid) r).reason())
        .log();
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
        rotation == null
            && current != null
            && !cb.block().commands().isEmpty()
            && Arrays.equals(cb.block().header().txRoot(), Block.txRoot(current.commands()));
    if (ownDraft) {
      current = null;
      while (!sequencer.control(new Sequencer.Committed(height))) {
        Thread.onSpinWait();
      }
    } else if (rotation == null && current != null && !cb.block().commands().isEmpty()) {
      // Someone else's non-empty block landed first: our draft is stale.
      epoch++;
      current = null;
      drafts.clear();
      LOG.atWarn()
          .setMessage("rollback")
          .addKeyValue("validator", cfg.me())
          .addKeyValue("height", height)
          .log();
      while (!sequencer.control(new Sequencer.Rollback(committed.copy(), List.of()))) {
        Thread.onSpinWait();
      }
    }
    List<ExecResult> results = v.results();
    if (rotation != null) {
      if (myProposalHash != null
          && !Arrays.equals(myProposalHash, cb.block().hash())
          && myProposalFirstTradeSeq >= 0) {
        LOG.atInfo()
            .setMessage("revoke")
            .addKeyValue("validator", cfg.me())
            .addKeyValue("height", height)
            .addKeyValue("fromTradeSeq", myProposalFirstTradeSeq)
            .log();
        rotation
            .committedEvents()
            .onRevoke(myProposalFirstTradeSeq); // provisional results are void
      }
      myProposalHash = null;
      myProposalFirstTradeSeq = -1;
      RecordedEvents ev = events.get(ByteBuffer.wrap(cb.block().hash()));
      if (ev != null) {
        ev.replayTo(rotation.committedEvents());
      }
      rotation.pool().remove(cb.block().commands());
      ExecutionCore c = committed;
      rotation.pool().dropStale(c::lastNonce);
    }
    events.clear();
    // Empty blocks arrive every few hundred ms: log them at DEBUG only.
    (cb.block().commands().isEmpty() ? LOG.atDebug() : LOG.atInfo())
        .setMessage("committed")
        .addKeyValue("validator", cfg.me())
        .addKeyValue("height", height)
        .addKeyValue("round", cb.cert().round())
        .addKeyValue("proposer", cb.block().header().proposer())
        .addKeyValue("commands", cb.block().commands().size())
        .log();
    finality.onFinal(height, cb.block().commands(), results);
  }

  /** Skips queued work and lets the running task (possibly an fsync) finish; no interrupts. */
  @Override
  public void close() {
    closing = true;
    loop.shutdown();
    try {
      loop.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
