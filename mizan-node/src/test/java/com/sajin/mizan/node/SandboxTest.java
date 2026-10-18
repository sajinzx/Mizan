package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sandbox behind the dashboard (ADR 0017), end to end: bots trade, your order goes provisional
 * then final, every validator's root equals the shadow replay, a stopped validator recovers and
 * catches up, a 2|2 partition halts the chain without forking and healing resumes it, and
 * verify-chain passes on every data directory.
 */
@Timeout(240)
class SandboxTest {
  @TempDir Path dir;

  static SandboxView.Snapshot await(Sandbox s, String what, Predicate<SandboxView.Snapshot> p)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    SandboxView.Snapshot snap = s.snapshot();
    while (!p.test(snap)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "timed out waiting for " + what + "; last: height " + snap.height());
      }
      Thread.sleep(100);
      snap = s.snapshot();
    }
    return snap;
  }

  @Test
  void theWholeNetworkWorksAndProvesItself() throws Exception {
    try (Sandbox s = Sandbox.start(4, dir)) {
      // Market activity from the bots, seen through the shadow replica.
      SandboxView.Snapshot snap =
          await(s, "trades", x -> x.stats().trades() > 20 && x.height() > 10);
      SandboxView.Instrument mzn = snap.instruments().get(0);
      assertThat(mzn.symbol()).isEqualTo("MZN/USD");
      assertThat(mzn.bids()).isNotEmpty();
      assertThat(mzn.asks()).isNotEmpty();
      assertThat(mzn.candles()).isNotEmpty();
      assertThat(snap.stateRootVersion()).isEqualTo(2);

      // Your order: provisional or straight to FINAL, then FINAL with a height.
      long best = mzn.bestAsk();
      long nonce = s.place(0, Sandbox.MZN_USD, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, best, 3);
      snap =
          await(
              s,
              "your order final",
              x ->
                  x.myOrders().stream()
                      .anyMatch(o -> o.nonce() == nonce && o.status().startsWith("FINAL")));
      SandboxView.MyOrder mine =
          snap.myOrders().stream().filter(o -> o.nonce() == nonce).findFirst().orElseThrow();
      assertThat(mine.height()).isPositive();
      assertThat(mine.finalMillis()).isGreaterThanOrEqualTo(mine.ackMillis());
      assertThat(snap.myBalances())
          .extracting(SandboxView.Balance::name)
          .contains("USD", "MZN", "ORB");

      // Determinism: every running validator's root equals the shadow replay at its height.
      snap = await(s, "roots compared", x -> x.determinism().compared() >= 3);
      assertThat(snap.determinism().ok()).isTrue();

      // Stop a validator: the other three keep committing; restart: it recovers and catches up.
      long before = snap.height();
      s.stop(3);
      await(s, "progress without v3", x -> x.height() > before + 5);
      s.restart(3);
      snap =
          await(
              s,
              "v3 caught up",
              x -> x.validators().get(3).up() && x.validators().get(3).height() >= x.height() - 2);
      assertThat(snap.validators().get(3).restarts()).isEqualTo(1);

      // 2 | 2: no side has a quorum, so no block commits; heal: blocks resume.
      s.partition(Set.of(0, 1));
      Thread.sleep(1_500);
      long halted = s.snapshot().height();
      Thread.sleep(2_000);
      assertThat(s.snapshot().height())
          .as("no quorum on either side")
          .isLessThanOrEqualTo(halted + 1);
      s.heal();
      await(s, "progress after heal", x -> x.height() > halted + 5);

      // verify-chain on every data directory, read-only, while the network runs.
      List<String> verify = s.verifyAll();
      assertThat(verify).hasSize(4).allMatch(r -> r.contains("OK"), "verify-chain OK");

      snap = await(s, "roots compared again", x -> x.determinism().compared() >= 3);
      assertThat(snap.determinism().ok()).isTrue();
      assertThat(snap.events()).isNotEmpty();
      System.out.printf(
          "sandbox: height %d, %d commands, %d trades, %d bot orders; roots %d/%d equal the replay%n",
          snap.height(),
          snap.stats().commands(),
          snap.stats().trades(),
          snap.stats().botOrders(),
          snap.determinism().matching(),
          snap.determinism().compared());
    }
  }
}
