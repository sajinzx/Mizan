package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.net.Messages.ServerMessage;
import com.sajin.mizan.net.TcpGateway;
import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.sim.LoadGenerator;
import com.sajin.mizan.sim.MizanClient;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.SignedCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M4 end-to-end: real engine, sequencer, gateway and client over localhost TCP. */
@Timeout(300)
class NodeIntegrationTest {
  static final GenesisConfig G = DevGenesis.config();

  static Node start() throws Exception {
    return Node.startInMemory(Node.Settings.dev(G, 0), List.of());
  }

  /** Conservation on the committed core: every asset's total equals the sum of its deposits. */
  static void assertConservation(ExecutionCore core) {
    for (GenesisConfig.Asset a : G.assets()) {
      assertThat(core.ledgerTotal(a.id())).as("asset %d", a.id()).isEqualTo(core.supply(a.id()));
    }
    assertThat(core.supply(com.sajin.mizan.testkit.Fixtures.QUOTE)).isPositive();
  }

  @Test
  void orderLifecycleRetryAndTradePush() throws Exception {
    try (Node node = start()) {
      var p = LoadGenerator.Params.of("127.0.0.1", node.gatewayPort(), 2, 0, 50, 1);
      LoadGenerator.setup(p);
      List<ServerMessage> pushes = new CopyOnWriteArrayList<>();
      try (MizanClient seller = new MizanClient("127.0.0.1", node.gatewayPort(), pushes::add);
          MizanClient buyer = new MizanClient("127.0.0.1", node.gatewayPort())) {
        SignedCommand ask = SignedCommands.limit(50, 2, 1, Side.SELL, 100, 5, TimeInForce.GTC);
        ServerMessage a = seller.submit(ask, Duration.ofSeconds(10));
        assertThat(a).isInstanceOf(Messages.Accepted.class);
        Messages.Final f = seller.finality(50, 2).get(10, TimeUnit.SECONDS);
        assertThat(f.status()).isEqualTo(Messages.Final.ACCEPTED);
        assertThat(f.orderId()).isEqualTo(((Messages.Accepted) a).orderId());

        SignedCommand bid = SignedCommands.limit(51, 2, 1, Side.BUY, 101, 2, TimeInForce.GTC);
        assertThat(buyer.submit(bid, Duration.ofSeconds(10))).isInstanceOf(Messages.Accepted.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pushes.stream().noneMatch(m -> m instanceof Messages.TradeMsg)
            && System.nanoTime() < deadline) {
          Thread.sleep(10);
        }
        assertThat(pushes).anyMatch(m -> m instanceof Messages.TradeMsg t && t.trade().qty() == 2);

        // Retry of an already final command: same answer, not executed again.
        ServerMessage again = seller.submit(ask, Duration.ofSeconds(10));
        assertThat(again).isInstanceOf(Messages.Final.class);
        assertThat(((Messages.Final) again).orderId()).isEqualTo(f.orderId());
        assertThat(seller.queryStatus(50, 2).get(5, TimeUnit.SECONDS).state())
            .isEqualTo(Messages.Status.FINAL);

        // Junk signatures are stopped at the gateway.
        ServerMessage forged =
            buyer.submit(
                SignedCommands.withBadSignature(
                    SignedCommands.limit(51, 3, 1, Side.BUY, 99, 1, TimeInForce.GTC)),
                Duration.ofSeconds(10));
        assertThat(forged).isInstanceOf(Messages.Rejected.class);
      }
      assertThat(node.verifier().hits()).as("engine re-verification hits the cache").isPositive();
    }
  }

  @Test
  void loadKeepsConservationAndLeaksNoThreads() throws Exception {
    Node node = start();
    LoadGenerator.Result r;
    try {
      var p = LoadGenerator.Params.of("127.0.0.1", node.gatewayPort(), 20, 100, 100, 7);
      LoadGenerator.setup(p);
      r = LoadGenerator.run(p);
      r.print(System.out);
      awaitHeightStable(node);
    } finally {
      node.close();
    }
    assertThat(r.errors()).isZero();
    assertThat(r.accepted() + r.rejected()).isEqualTo(2_000);
    assertThat(r.activeThreadsAfterJoin()).isZero();
    assertConservation(node.committedCore());
  }

  /** M4 gate: 100 traders × 1,000 orders over TCP; conservation afterwards. */
  @Test
  @Tag("slow")
  void hundredTradersThousandOrders() throws Exception {
    Node node = start();
    LoadGenerator.Result r;
    try {
      var p = LoadGenerator.Params.of("127.0.0.1", node.gatewayPort(), 100, 1_000, 1_000, 11);
      LoadGenerator.setup(p);
      r = LoadGenerator.run(p);
      r.print(System.out);
      awaitHeightStable(node);
    } finally {
      node.close();
    }
    assertThat(r.errors()).isZero();
    assertThat(r.accepted() + r.rejected()).isEqualTo(100_000);
    assertThat(r.activeThreadsAfterJoin()).isZero();
    assertConservation(node.committedCore());
  }

  /**
   * Flooding more commands than the sequencer queue holds yields {@code BUSY} quickly instead of
   * unbounded queueing: every request is answered, latency stays bounded, nothing runs out of
   * memory.
   */
  @Test
  void floodYieldsBusyWithBoundedLatency() throws Exception {
    GenesisConfig g = G;
    Node.Settings s =
        new Node.Settings(
            g,
            TcpGateway.Config.defaults(0).withLimits(1_000_000, 1_000_000),
            new Sequencer.Config(64, 1 << 10, 50, 5_000_000L, 1),
            false); // no gateway pre-check: the engine's own verification is the bottleneck
    try (Node node = Node.startInMemory(s, List.of())) {
      var p = LoadGenerator.Params.of("127.0.0.1", node.gatewayPort(), 10, 0, 300, 3);
      LoadGenerator.setup(p);
      Histogram latency = new Histogram(3);
      int busy = 0;
      int answered = 0;
      List<MizanClient> clients = new ArrayList<>();
      try {
        // Pre-sign everything so the burst arrives far faster than the engine can verify.
        List<List<SignedCommand>> signed = new ArrayList<>();
        for (int t = 0; t < 10; t++) {
          List<SignedCommand> mine = new ArrayList<>();
          for (int k = 0; k < 500; k++) {
            mine.add(
                CommandSigner.ED25519.sign(
                    com.sajin.mizan.testkit.Fixtures.CHAIN_ID,
                    300 + t,
                    10 + k,
                    new com.sajin.mizan.core.Place(
                        k,
                        1,
                        Side.BUY,
                        com.sajin.mizan.core.OrderType.LIMIT,
                        TimeInForce.GTC,
                        90,
                        1)));
          }
          signed.add(mine);
          clients.add(new MizanClient("127.0.0.1", node.gatewayPort()));
        }
        List<CompletableFuture<ServerMessage>> fs = new ArrayList<>();
        List<Long> sentAt = new ArrayList<>();
        for (int k = 0; k < 500; k++) {
          for (int t = 0; t < 10; t++) {
            sentAt.add(System.nanoTime());
            fs.add(clients.get(t).send(signed.get(t).get(k)));
          }
        }
        for (int i = 0; i < fs.size(); i++) {
          ServerMessage m = fs.get(i).get(30, TimeUnit.SECONDS);
          answered++;
          latency.recordValue(Math.max(1, (System.nanoTime() - sentAt.get(i)) / 1_000));
          if (m instanceof Messages.Busy) {
            busy++;
          }
        }
      } finally {
        for (MizanClient c : clients) {
          c.close();
        }
      }
      System.out.printf(
          "flood: answered=%d busy=%d p99=%dus max=%dus%n",
          answered, busy, latency.getValueAtPercentile(99), latency.getMaxValue());
      assertThat(answered).isEqualTo(5_000);
      assertThat(busy).isPositive();
      assertThat(latency.getMaxValue()).as("bounded latency (us)").isLessThan(10_000_000);
    }
  }

  static void awaitHeightStable(Node node) throws InterruptedException {
    long last = -1;
    while (node.height() != last) {
      last = node.height();
      Thread.sleep(300);
    }
  }
}
