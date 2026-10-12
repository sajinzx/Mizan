package com.sajin.mizan.engine;

import com.sajin.mizan.core.CommandSink;
import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.Metrics;
import com.sajin.mizan.core.ResultListener;
import com.sajin.mizan.core.SignedCommand;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

/**
 * The single writer (§6.4, ADR 0001). The {@code sequencer} thread owns the speculative {@link
 * ExecutionCore}; every other thread talks to it through two bounded lock-free queues:
 *
 * <ul>
 *   <li>{@code inbound} (MPSC): signed commands from gateways. {@link #submit} never blocks, and
 *       {@code false} means {@code BUSY}.
 *   <li>{@code control} (MPSC): {@link Committed} and {@link Rollback} from the chain layer.
 * </ul>
 *
 * <p>Each command executes immediately on the speculative core, and its provisional result goes to
 * the {@link ResultListener}. Commands that passed authentication are appended to {@code pending};
 * {@code AUTH_FAILED} commands never enter a block (defect 1). All of {@code pending} is cut into a
 * {@link BlockDraft} when it reaches {@code maxBatch} or the cut interval elapses, as long as fewer
 * than {@code maxInflight} drafts await commit. {@code pending} never exceeds {@code maxBatch}:
 * while it is full and no draft can be cut, the sequencer stops reading {@code inbound}, which is
 * the backpressure (ADR 0010). The draft's state root is therefore always the current speculative
 * root. The wall clock decides only <em>when</em> to cut, never what executes.
 */
public final class Sequencer implements CommandSink {

  /**
   * A cut batch waiting for consensus. {@code stateRoot} is the speculative root after it. {@code
   * epoch} counts rollbacks: drafts from an earlier epoch are stale and must be ignored.
   */
  @SuppressWarnings("ArrayRecordComponent")
  public record BlockDraft(
      long epoch, long firstTradeSeq, List<SignedCommand> commands, byte[] stateRoot) {
    public BlockDraft {
      commands = List.copyOf(commands);
    }
  }

  /** Messages from the chain layer. */
  public sealed interface Control permits Committed, Rollback {}

  /** The oldest in-flight draft was committed at {@code height}. */
  public record Committed(long height) implements Control {}

  /**
   * In-flight drafts were not committed: restart from {@code freshCopy} (a copy of the committed
   * core, owned by the sequencer from now on) and re-execute, in order, the commands of every
   * in-flight draft, then {@code pending}, then {@code toReplay} (extra commands, e.g. from a
   * mempool). Nothing that was accepted provisionally is dropped.
   */
  public record Rollback(ExecutionCore freshCopy, List<SignedCommand> toReplay) implements Control {
    public Rollback {
      Objects.requireNonNull(freshCopy, "freshCopy");
      toReplay = List.copyOf(toReplay);
    }
  }

  /** Tuning. Capacities are rounded up to a power of two by the queues. */
  public record Config(
      int inboundCapacity,
      int controlCapacity,
      int maxBatch,
      long cutIntervalNanos,
      int maxInflight) {
    public Config {
      if (inboundCapacity <= 1 || controlCapacity <= 1 || maxBatch <= 0 || maxInflight <= 0) {
        throw new IllegalArgumentException("bad sequencer config");
      }
      if (cutIntervalNanos <= 0) {
        throw new IllegalArgumentException("cut interval must be positive");
      }
    }

    /** {@code maxBatch} and interval from genesis block params, with default queue sizes. */
    public static Config of(int maxBatch, long cutIntervalMillis, int maxInflight) {
      return new Config(1 << 16, 1 << 10, maxBatch, cutIntervalMillis * 1_000_000L, maxInflight);
    }
  }

  private static final int POLL_LIMIT = 256;

  private final ManyToOneConcurrentArrayQueue<SignedCommand> inbound;
  private final ManyToOneConcurrentArrayQueue<Control> control;
  private final EngineListener listener;
  private final ResultListener results;
  private final Consumer<BlockDraft> drafts;
  private final Config config;
  private final LongSupplier nanoClock;
  private final Metrics.Timer executeTimer;
  private final Metrics.Timer cutToCommit;
  private final Metrics.Counter rollbacks;

  // ---- owned by the sequencer thread ----
  private ExecutionCore speculative;
  private final List<SignedCommand> pending = new ArrayList<>();
  private long pendingFirstTradeSeq;
  private long pendingSinceNanos;
  private final ArrayDeque<BlockDraft> inflight = new ArrayDeque<>();
  private final ArrayDeque<Long> inflightCutNanos = new ArrayDeque<>();
  private final ArrayDeque<SignedCommand> replay = new ArrayDeque<>();
  private long executed;
  private long epoch;

  private volatile boolean running;
  private Thread thread;

  public Sequencer(
      ExecutionCore speculative,
      EngineListener listener,
      ResultListener results,
      Consumer<BlockDraft> drafts,
      Config config,
      LongSupplier nanoClock) {
    this(speculative, listener, results, drafts, config, nanoClock, Metrics.NOOP);
  }

  public Sequencer(
      ExecutionCore speculative,
      EngineListener listener,
      ResultListener results,
      Consumer<BlockDraft> drafts,
      Config config,
      LongSupplier nanoClock,
      Metrics metrics) {
    this.speculative = Objects.requireNonNull(speculative, "speculative");
    this.listener = Objects.requireNonNull(listener, "listener");
    this.results = Objects.requireNonNull(results, "results");
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.config = Objects.requireNonNull(config, "config");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.inbound = new ManyToOneConcurrentArrayQueue<>(config.inboundCapacity());
    this.control = new ManyToOneConcurrentArrayQueue<>(config.controlCapacity());
    this.pendingFirstTradeSeq = speculative.nextTradeSeq();
    this.executeTimer = metrics.timer("sequencer_execute");
    this.cutToCommit = metrics.timer("sequencer_cut_to_commit");
    this.rollbacks = metrics.counter("sequencer_rollbacks");
    metrics.gauge("sequencer_inbound_depth", inbound::size);
  }

  // ------------------------------------------------------------------ any thread

  /** Enqueues {@code c}; {@code false} if the inbound queue is full ({@code BUSY}). */
  @Override
  public boolean submit(SignedCommand c) {
    return inbound.offer(Objects.requireNonNull(c, "command"));
  }

  /** Enqueues a control message from the chain layer; {@code false} if full. */
  public boolean control(Control msg) {
    return control.offer(Objects.requireNonNull(msg, "control"));
  }

  public int inboundSize() {
    return inbound.size();
  }

  /** Starts the dedicated {@code sequencer} platform thread. */
  public synchronized void start() {
    if (thread != null) {
      throw new IllegalStateException("already started");
    }
    running = true;
    thread = new Thread(this::runLoop, "sequencer");
    thread.start();
  }

  /** Stops the loop and waits for the thread to exit. */
  public synchronized void stop() throws InterruptedException {
    running = false;
    if (thread != null) {
      thread.join();
      thread = null;
    }
  }

  private void runLoop() {
    IdleStrategy idle = new BackoffIdleStrategy(100, 10, 1_000, 1_000_000);
    while (running) {
      idle.idle(doWork());
    }
  }

  // ------------------------------------------------------------------ sequencer thread only

  /**
   * One duty cycle: apply control messages; execute up to a bounded number of commands (rollback
   * replays first, then inbound), cutting a draft whenever {@code pending} fills; then cut on the
   * interval if due. Returns the amount of work done (0 = idle). Called by the sequencer thread, or
   * directly by single-threaded tests.
   */
  public int doWork() {
    int work = 0;
    Control msg;
    while ((msg = control.poll()) != null) {
      handle(msg);
      work++;
    }
    while (work < POLL_LIMIT) {
      if (pending.size() >= config.maxBatch() && !maybeCut()) {
        break; // backpressure: full and no draft slot
      }
      SignedCommand c = replay.isEmpty() ? inbound.poll() : replay.pollFirst();
      if (c == null) {
        break;
      }
      executeAndAppend(c);
      work++;
    }
    if (maybeCut()) {
      work++;
    }
    return work;
  }

  private void executeAndAppend(SignedCommand c) {
    long seqBefore = speculative.nextTradeSeq();
    long t0 = nanoClock.getAsLong();
    ExecResult r = speculative.execute(c, listener);
    executeTimer.recordNanos(nanoClock.getAsLong() - t0);
    executed++;
    results.onResult(c, r);
    if (r.status() != ExecResult.Status.AUTH_FAILED) { // defect 1
      if (pending.isEmpty()) {
        pendingFirstTradeSeq = seqBefore;
        pendingSinceNanos = nanoClock.getAsLong();
      }
      pending.add(c);
    }
  }

  private boolean maybeCut() {
    if (pending.isEmpty() || inflight.size() >= config.maxInflight()) {
      return false;
    }
    boolean full = pending.size() >= config.maxBatch();
    boolean due = nanoClock.getAsLong() - pendingSinceNanos >= config.cutIntervalNanos();
    if (!full && !due) {
      return false;
    }
    BlockDraft draft =
        new BlockDraft(epoch, pendingFirstTradeSeq, pending, speculative.stateRoot());
    pending.clear();
    inflight.addLast(draft);
    inflightCutNanos.addLast(nanoClock.getAsLong());
    drafts.accept(draft);
    return true;
  }

  private void handle(Control msg) {
    switch (msg) {
      case Committed c -> {
        if (inflight.pollFirst() == null) {
          throw new InvariantViolationError("commit at height " + c.height() + " with no draft");
        }
        cutToCommit.recordNanos(nanoClock.getAsLong() - inflightCutNanos.pollFirst());
      }
      case Rollback r -> {
        long from =
            inflight.isEmpty() ? pendingFirstTradeSeq : inflight.peekFirst().firstTradeSeq();
        listener.onRevoke(from); // defect 6
        speculative = r.freshCopy();
        ArrayDeque<SignedCommand> again = new ArrayDeque<>();
        inflight.forEach(d -> again.addAll(d.commands()));
        again.addAll(pending);
        again.addAll(replay); // a previous rollback's replay not yet finished
        again.addAll(r.toReplay());
        inflight.clear();
        inflightCutNanos.clear();
        rollbacks.inc();
        pending.clear();
        replay.clear();
        replay.addAll(again); // re-executed by doWork, under the same batch bound
        epoch++;
        pendingFirstTradeSeq = speculative.nextTradeSeq();
      }
    }
  }

  // ------------------------------------------------------------------ test views (sequencer
  // thread)

  int pendingSize() {
    return pending.size();
  }

  int inflightSize() {
    return inflight.size();
  }

  long epoch() {
    return epoch;
  }

  long executedCount() {
    return executed;
  }

  ExecutionCore speculative() {
    return speculative;
  }
}
