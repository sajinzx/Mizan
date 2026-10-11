package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.ExecResult.Status;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.RecordingListener;
import com.sajin.mizan.testkit.SignedCommands;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M3-T1: the single-writer sequencer. */
class SequencerTest {
  static final long MS = 1_000_000L;

  /** A fresh genesis core with accounts {@code 2..lastAccount} registered and funded. */
  static ExecutionCore fundedCore(long lastAccount) {
    ExecutionCore core = ExecutionCore.genesis(Fixtures.genesis(), CommandSigner.FAST_VERIFIER);
    long adminNonce = 0;
    for (long a = 2; a <= lastAccount; a++) {
      exec(core, a, 1, new Register(Fixtures.accountSigner(a).publicKey()));
      exec(core, Accounts.ADMIN_ACCOUNT, ++adminNonce, new Deposit(a, Fixtures.QUOTE, 1L << 40));
      exec(core, Accounts.ADMIN_ACCOUNT, ++adminNonce, new Deposit(a, Fixtures.BASE, 1L << 30));
    }
    return core;
  }

  private static void exec(
      ExecutionCore core, long account, long nonce, com.sajin.mizan.core.OrderCommand body) {
    ExecResult r =
        core.execute(
            CommandSigner.FAST.sign(Fixtures.CHAIN_ID, account, nonce, body), ExecutionCore.SILENT);
    assertThat(r.status()).isEqualTo(Status.ACCEPTED);
  }

  static SignedCommand order(long account, long nonce, Side side, long price) {
    return CommandSigner.FAST.sign(
        Fixtures.CHAIN_ID,
        account,
        nonce,
        new Place(nonce, Fixtures.INSTRUMENT, side, OrderType.LIMIT, TimeInForce.GTC, price, 1));
  }

  /** Fake chain layer + clock for single-threaded tests. */
  static final class Harness {
    final AtomicLong now = new AtomicLong();
    final List<Sequencer.BlockDraft> drafts = new ArrayList<>();
    final List<SignedCommand> resultOrder = new ArrayList<>();
    final List<ExecResult> results = new ArrayList<>();
    final RecordingListener events = new RecordingListener();
    final ExecutionCore initial;
    final Sequencer seq;

    Harness(ExecutionCore core, Sequencer.Config config) {
      this.initial = core.copy();
      this.seq =
          new Sequencer(
              core,
              events,
              (c, r) -> {
                resultOrder.add(c);
                results.add(r);
              },
              drafts::add,
              config,
              now::get);
    }

    void submitAll(List<SignedCommand> cs) {
      for (SignedCommand c : cs) {
        assertThat(seq.submit(c)).isTrue();
      }
    }

    void drain() {
      while (seq.doWork() > 0) {
        // keep going
      }
    }
  }

  static Sequencer.Config config(int maxBatch, int maxInflight) {
    return new Sequencer.Config(1 << 10, 64, maxBatch, 10 * MS, maxInflight);
  }

  // ------------------------------------------------------------------ defect 1

  /**
   * Defect 1: commands that fail authentication must never be put into a block (validators would
   * reject the whole block). Business rejects are valid no-ops and do go in.
   */
  @Test
  void authFailedCommandNeverEntersBlock() {
    Harness h = new Harness(fundedCore(3), config(100, 1));
    SignedCommand good1 = order(2, 2, Side.BUY, 100);
    SignedCommand badSig = SignedCommands.withBadSignature(order(2, 3, Side.BUY, 100));
    SignedCommand stale = order(2, 2, Side.BUY, 101); // nonce 2 already used
    SignedCommand rejected = order(3, 2, Side.BUY, 0); // business reject: price 0
    SignedCommand good2 = order(2, 3, Side.SELL, 105);
    h.submitAll(List.of(good1, badSig, stale, rejected, good2));
    h.drain();
    h.now.addAndGet(10 * MS);
    h.drain();

    assertThat(h.results)
        .extracting(ExecResult::status)
        .containsExactly(
            Status.ACCEPTED,
            Status.AUTH_FAILED,
            Status.AUTH_FAILED,
            Status.REJECTED,
            Status.ACCEPTED);
    assertThat(h.drafts)
        .singleElement()
        .satisfies(d -> assertThat(d.commands()).containsExactly(good1, rejected, good2));
  }

  // ------------------------------------------------------------------ backpressure and cutting

  @Test
  void fullInboundQueueReturnsBusyAndSequencerIsUnaffected() {
    ExecutionCore core = fundedCore(2);
    Harness h = new Harness(core, new Sequencer.Config(4, 4, 100, 10 * MS, 1));
    List<SignedCommand> accepted = new ArrayList<>();
    int busy = 0;
    for (long n = 2; n < 12; n++) {
      SignedCommand c = order(2, n, Side.BUY, 90);
      if (h.seq.submit(c)) {
        accepted.add(c);
      } else {
        busy++;
      }
    }
    assertThat(accepted).hasSize(4);
    assertThat(busy).isEqualTo(6);
    h.drain();
    assertThat(h.resultOrder).containsExactlyElementsOf(accepted);
    assertThat(h.results).extracting(ExecResult::status).containsOnly(Status.ACCEPTED);
    // Room again after draining; a retry of a BUSY command with the same nonce succeeds.
    assertThat(h.seq.submit(order(2, 6, Side.BUY, 90))).isTrue();
    h.drain();
    assertThat(h.results.get(4).status()).isEqualTo(Status.ACCEPTED);
  }

  @Test
  void cutsOnBatchSizeOrIntervalAndAppliesBackpressure() {
    Harness h = new Harness(fundedCore(2), config(3, 1));
    h.submitAll(List.of(order(2, 2, Side.BUY, 90), order(2, 3, Side.BUY, 90)));
    h.drain();
    assertThat(h.drafts).isEmpty(); // below batch, interval not elapsed
    h.now.addAndGet(10 * MS);
    h.drain();
    assertThat(h.drafts).hasSize(1);
    assertThat(h.drafts.get(0).commands()).hasSize(2);

    // One draft in flight (maxInflight 1): the sequencer fills pending to maxBatch, then stops.
    for (long n = 4; n < 11; n++) {
      assertThat(h.seq.submit(order(2, n, Side.BUY, 90))).isTrue();
    }
    h.drain();
    assertThat(h.drafts).hasSize(1);
    assertThat(h.seq.pendingSize()).isEqualTo(3);
    assertThat(h.seq.inboundSize()).isEqualTo(4);

    assertThat(h.seq.control(new Sequencer.Committed(1))).isTrue();
    h.drain();
    assertThat(h.drafts).hasSize(2);
    assertThat(h.drafts.get(1).commands()).hasSize(3);
    assertThat(h.seq.pendingSize()).isEqualTo(3); // next batch already executed, waiting again
    assertThat(h.seq.inboundSize()).isEqualTo(1);
  }

  @Test
  void draftRootsMatchAValidatorReplay() {
    Harness h = new Harness(fundedCore(4), config(5, 100));
    long[] nonces = {0, 0, 1, 1, 1};
    for (int i = 0; i < 37; i++) {
      long a = 2L + i % 3;
      h.submitAll(
          List.of(order(a, ++nonces[(int) a], i % 2 == 0 ? Side.BUY : Side.SELL, 98 + i % 5)));
    }
    h.drain();
    h.now.addAndGet(10 * MS);
    h.drain();
    assertThat(h.drafts).hasSize(8);
    ExecutionCore validator = h.initial.copy();
    long firstSeq = validator.nextTradeSeq();
    for (Sequencer.BlockDraft d : h.drafts) {
      assertThat(d.firstTradeSeq()).isEqualTo(firstSeq);
      for (SignedCommand c : d.commands()) {
        assertThat(validator.execute(c, ExecutionCore.SILENT).status())
            .isNotEqualTo(Status.AUTH_FAILED);
      }
      assertThat(validator.stateRoot()).isEqualTo(d.stateRoot());
      firstSeq = validator.nextTradeSeq();
    }
    assertThat(h.events.trades()).isNotEmpty();
  }

  // ------------------------------------------------------------------ rollback (against a fake
  // chain)

  @Test
  void rollbackRevokesFromOldestUncommittedAndReplays() {
    Harness h = new Harness(fundedCore(3), config(2, 4));
    // Draft 1: a resting ask and a crossing bid (trade seq 1). Committed.
    h.submitAll(List.of(order(2, 2, Side.SELL, 100), order(3, 2, Side.BUY, 100)));
    h.drain();
    assertThat(h.drafts).hasSize(1);
    ExecutionCore committed = h.initial.copy();
    h.drafts.get(0).commands().forEach(c -> committed.execute(c, ExecutionCore.SILENT));
    h.seq.control(new Sequencer.Committed(1));
    // Draft 2 (trade seq 2) in flight, plus one pending command.
    h.submitAll(
        List.of(
            order(2, 3, Side.SELL, 100), order(3, 3, Side.BUY, 100), order(2, 4, Side.SELL, 110)));
    h.drain();
    assertThat(h.drafts).hasSize(2);
    long draft2First = h.drafts.get(1).firstTradeSeq();
    assertThat(draft2First).isEqualTo(2);
    h.events.drain();

    List<SignedCommand> toReplay = new ArrayList<>(h.drafts.get(1).commands());
    toReplay.add(order(2, 4, Side.SELL, 110));
    h.seq.control(new Sequencer.Rollback(committed.copy(), toReplay));
    h.drain();

    assertThat(h.events.events())
        .filteredOn(e -> e instanceof RecordingListener.Revoked)
        .containsExactly(new RecordingListener.Revoked(draft2First));
    ExecutionCore expected = committed.copy();
    toReplay.forEach(c -> expected.execute(c, ExecutionCore.SILENT));
    assertThat(h.seq.speculative().stateRoot()).isEqualTo(expected.stateRoot());
  }

  // ------------------------------------------------------------------ concurrency

  /**
   * Many producers on real threads: every command is executed exactly once, each producer's
   * commands execute in submission order, and the drafts contain exactly the authenticated commands
   * in execution order.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void manyProducersNoLostOrDuplicatedCommands() throws Exception {
    int producers = 8;
    int perProducer = 3_000;
    ExecutionCore core = fundedCore(1 + producers);
    ExecutionCore initial = core.copy();
    ConcurrentLinkedQueue<SignedCommand> executed = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<ExecResult> statuses = new ConcurrentLinkedQueue<>();
    List<Sequencer.BlockDraft> drafts = Collections.synchronizedList(new ArrayList<>());
    Sequencer[] holder = new Sequencer[1];
    Sequencer seq =
        new Sequencer(
            core,
            ExecutionCore.SILENT,
            (c, r) -> {
              executed.add(c);
              statuses.add(r);
            },
            d -> {
              drafts.add(d);
              holder[0].control(new Sequencer.Committed(drafts.size())); // instant fake commit
            },
            new Sequencer.Config(1 << 8, 1 << 10, 64, 1 * MS, 2),
            System::nanoTime);
    holder[0] = seq;
    seq.start();

    CountDownLatch go = new CountDownLatch(1);
    AtomicLong busy = new AtomicLong();
    List<List<SignedCommand>> sent = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();
    for (int p = 0; p < producers; p++) {
      long account = 2L + p;
      List<SignedCommand> mine = new ArrayList<>();
      for (int i = 0; i < perProducer; i++) {
        mine.add(order(account, 2 + i, i % 2 == 0 ? Side.BUY : Side.SELL, 95 + (i + p) % 11));
      }
      sent.add(mine);
      Thread t =
          Thread.ofPlatform()
              .name("producer-" + p)
              .start(
                  () -> {
                    try {
                      go.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                    for (SignedCommand c : mine) {
                      while (!seq.submit(c)) { // BUSY: retry with the same command
                        busy.incrementAndGet();
                        Thread.onSpinWait();
                      }
                    }
                  });
      threads.add(t);
    }
    go.countDown();
    for (Thread t : threads) {
      t.join();
    }
    long total = (long) producers * perProducer;
    while (executed.size() < total) {
      Thread.sleep(5);
    }
    Thread.sleep(20); // let the interval cut flush the last batch
    seq.stop();

    List<SignedCommand> order = new ArrayList<>(executed);
    assertThat(order).hasSize((int) total);
    IdentityHashMap<SignedCommand, Integer> seen = new IdentityHashMap<>();
    for (int i = 0; i < order.size(); i++) {
      assertThat(seen.put(order.get(i), i)).as("duplicate execution").isNull();
    }
    for (List<SignedCommand> mine : sent) {
      int last = -1;
      for (SignedCommand c : mine) {
        Integer at = seen.get(c);
        assertThat(at).as("lost command").isNotNull();
        assertThat(at).as("per-producer FIFO").isGreaterThan(last);
        last = at;
      }
    }
    assertThat(statuses).extracting(ExecResult::status).doesNotContain(Status.AUTH_FAILED);
    List<SignedCommand> inDrafts = new ArrayList<>();
    drafts.forEach(d -> inDrafts.addAll(d.commands()));
    assertThat(inDrafts).containsExactlyElementsOf(order);

    ExecutionCore replayed = initial.copy();
    order.forEach(c -> replayed.execute(c, ExecutionCore.SILENT));
    assertThat(drafts.get(drafts.size() - 1).stateRoot()).isEqualTo(replayed.stateRoot());
    System.out.println(
        "sequencer: "
            + total
            + " commands from "
            + producers
            + " producers, "
            + drafts.size()
            + " drafts, "
            + busy.get()
            + " BUSY retries");
  }
}
