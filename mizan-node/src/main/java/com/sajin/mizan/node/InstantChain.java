package com.sajin.mizan.node;

import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory "chain" for dev mode before persistence existed (M4). It commits every draft
 * immediately on its own {@code consensus} thread: it applies the draft to the committed core,
 * checks the root, acknowledges the sequencer and emits {@code FINAL}. There is no fsync, so
 * nothing survives a restart. Superseded by the block store and single-node consensus in M6, and
 * kept for in-memory tests.
 */
final class InstantChain implements ChainLayer {
  private final LinkedBlockingQueue<Sequencer.BlockDraft> drafts = new LinkedBlockingQueue<>();
  private final ExecutionCore committed;
  private final FinalityListener finality;
  private volatile Sequencer sequencer;
  private volatile boolean running = true;
  private final Thread thread;
  private final AtomicLong height = new AtomicLong();

  InstantChain(ExecutionCore committed, FinalityListener finality) {
    this.committed = committed;
    this.finality = finality;
    this.thread = Thread.ofPlatform().name("consensus").daemon(true).unstarted(this::loop);
  }

  @Override
  public void start(Sequencer seq) {
    this.sequencer = seq;
    thread.start();
  }

  /** Called on the sequencer thread; never blocks (the queue is bounded by {@code maxInflight}). */
  @Override
  public void accept(Sequencer.BlockDraft d) {
    drafts.add(d);
  }

  private void loop() {
    try {
      while (running) {
        Sequencer.BlockDraft d = drafts.take();
        List<ExecResult> results = new ArrayList<>(d.commands().size());
        for (SignedCommand c : d.commands()) {
          results.add(committed.execute(c, ExecutionCore.SILENT));
        }
        if (!Arrays.equals(committed.stateRoot(), d.stateRoot())) {
          throw new InvariantViolationError(
              "committed root differs from draft root at " + (height.get() + 1));
        }
        long h = height.incrementAndGet();
        while (!sequencer.control(new Sequencer.Committed(h))) {
          Thread.onSpinWait();
        }
        finality.onFinal(h, d.commands(), results);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public long height() {
    return height.get();
  }

  @Override
  public ExecutionCore committedCore() {
    return committed;
  }

  @Override
  public void close() {
    running = false;
    thread.interrupt();
    try {
      thread.join(2_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
