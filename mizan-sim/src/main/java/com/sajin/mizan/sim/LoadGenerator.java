package com.sajin.mizan.sim;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.net.Messages.ServerMessage;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * Drives a node over TCP with many concurrent traders (M4-T5). Each trader is a platform thread in
 * the {@code "sim-traders"} {@link ThreadGroup} with its own connection and account. It sends limit
 * orders around price 100 one at a time, waits for the ack (retrying {@code BUSY} with the same
 * nonce), and records ingest→ack latency in an HdrHistogram. Client order ids come from one shared
 * {@link AtomicLong}.
 *
 * <p>Setup registers and funds each trader via the fixture admin key, so it works against a dev
 * node built from the fixture genesis.
 */
public final class LoadGenerator {

  /** Run parameters. */
  public record Params(
      String host,
      int port,
      int traders,
      int ordersPerTrader,
      long firstAccount,
      long seed,
      CommandSigner signer) {

    /** Real Ed25519 signatures with the fixture keys. */
    public static Params of(
        String host, int port, int traders, int ordersPerTrader, long firstAccount, long seed) {
      return new Params(
          host, port, traders, ordersPerTrader, firstAccount, seed, CommandSigner.ED25519);
    }
  }

  /** Run outcome. Latencies are in microseconds. */
  public record Result(
      long sent,
      long accepted,
      long rejected,
      long busyRetries,
      long errors,
      Histogram latencyMicros,
      long elapsedNanos,
      int activeThreadsAfterJoin) {

    public double throughputPerSecond() {
      return sent * 1e9 / Math.max(1, elapsedNanos);
    }

    public void print(PrintStream out) {
      out.printf(
          "sent=%d accepted=%d rejected=%d busyRetries=%d errors=%d elapsed=%.2fs throughput=%.0f/s%n",
          sent, accepted, rejected, busyRetries, errors, elapsedNanos / 1e9, throughputPerSecond());
      out.printf(
          "ack latency us: p50=%d p99=%d p99.9=%d max(observed)=%d%n",
          latencyMicros.getValueAtPercentile(50),
          latencyMicros.getValueAtPercentile(99),
          latencyMicros.getValueAtPercentile(99.9),
          latencyMicros.getMaxValue());
      out.printf("sim-traders active after join: %d%n", activeThreadsAfterJoin);
    }
  }

  private LoadGenerator() {}

  /** Registers and funds {@code traders} accounts starting at {@code firstAccount}. */
  public static void setup(Params p) throws IOException, InterruptedException, TimeoutException {
    long adminNonce = System.currentTimeMillis() * 1_000; // unique per run, increasing
    try (MizanClient admin = new MizanClient(p.host(), p.port())) {
      for (int i = 0; i < p.traders(); i++) {
        long account = p.firstAccount() + i;
        expectOk(
            admin.submit(
                p.signer()
                    .sign(
                        Fixtures.CHAIN_ID,
                        account,
                        1,
                        new Register(Fixtures.accountSigner(account).publicKey())),
                Duration.ofSeconds(30)),
            "register " + account,
            true);
        for (int asset : new int[] {Fixtures.QUOTE, Fixtures.BASE}) {
          expectOk(
              admin.submit(
                  p.signer()
                      .sign(
                          Fixtures.CHAIN_ID,
                          Accounts.ADMIN_ACCOUNT,
                          ++adminNonce,
                          new Deposit(
                              account, asset, asset == Fixtures.QUOTE ? 1L << 40 : 1L << 32)),
                  Duration.ofSeconds(30)),
              "deposit " + account,
              false);
        }
      }
    }
  }

  private static void expectOk(ServerMessage m, String what, boolean allowRejected) {
    boolean ok =
        m instanceof Messages.Accepted
            || (m instanceof Messages.Final f && f.status() == Messages.Final.ACCEPTED)
            || (allowRejected && m instanceof Messages.Rejected); // already registered on reruns
    if (!ok) {
      throw new IllegalStateException(what + " failed: " + m);
    }
  }

  /** Runs the traders and returns the aggregated result. Call {@link #setup} first. */
  public static Result run(Params p) throws InterruptedException {
    ThreadGroup group = new ThreadGroup("sim-traders");
    AtomicLong clientOrderIds = new AtomicLong();
    AtomicLong accepted = new AtomicLong();
    AtomicLong rejected = new AtomicLong();
    AtomicLong busy = new AtomicLong();
    AtomicLong errors = new AtomicLong();
    Recorder recorder = new Recorder(3);
    List<Thread> threads = new ArrayList<>();
    long nonceBase = Math.max(2, System.currentTimeMillis() * 1_000); // fresh nonces on every run
    long start = System.nanoTime();
    for (int i = 0; i < p.traders(); i++) {
      long account = p.firstAccount() + i;
      SplittableRandom rnd = new SplittableRandom(p.seed() + i);
      Thread t =
          new Thread(
              group,
              () ->
                  trade(
                      p,
                      account,
                      nonceBase,
                      rnd,
                      clientOrderIds,
                      accepted,
                      rejected,
                      busy,
                      errors,
                      recorder),
              "trader-" + account);
      threads.add(t);
      t.start();
    }
    for (Thread t : threads) {
      t.join();
    }
    long elapsed = System.nanoTime() - start;
    long sent = (long) p.traders() * p.ordersPerTrader();
    return new Result(
        sent,
        accepted.get(),
        rejected.get(),
        busy.get(),
        errors.get(),
        recorder.getIntervalHistogram(),
        elapsed,
        group.activeCount());
  }

  private static void trade(
      Params p,
      long account,
      long nonceBase,
      SplittableRandom rnd,
      AtomicLong clientOrderIds,
      AtomicLong accepted,
      AtomicLong rejected,
      AtomicLong busy,
      AtomicLong errors,
      Recorder recorder) {
    try (MizanClient c = new MizanClient(p.host(), p.port())) {
      for (int k = 0; k < p.ordersPerTrader(); k++) {
        Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
        long price = 95L + rnd.nextInt(11);
        long qty = 1L + rnd.nextInt(10);
        TimeInForce tif = rnd.nextInt(4) == 0 ? TimeInForce.IOC : TimeInForce.GTC;
        SignedCommand cmd =
            p.signer()
                .sign(
                    Fixtures.CHAIN_ID,
                    account,
                    nonceBase + k,
                    new Place(
                        clientOrderIds.incrementAndGet(),
                        Fixtures.INSTRUMENT,
                        side,
                        OrderType.LIMIT,
                        tif,
                        price,
                        qty));
        long t0 = System.nanoTime();
        ServerMessage m = c.submit(cmd, Duration.ofSeconds(60));
        recorder.recordValue(Math.max(1, (System.nanoTime() - t0) / 1_000));
        if (m instanceof Messages.Rejected) {
          rejected.incrementAndGet();
        } else {
          accepted.incrementAndGet();
        }
      }
      busy.addAndGet(c.busyRetries());
    } catch (IOException | TimeoutException e) {
      errors.incrementAndGet();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
