package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.OrderBook;
import com.sajin.mizan.engine.RestingOrder;
import com.sajin.mizan.net.md.DepthBook;
import com.sajin.mizan.net.md.MarketDataClient;
import com.sajin.mizan.net.md.MarketDataService;
import com.sajin.mizan.net.md.MdCodec;
import com.sajin.mizan.net.md.SnapshotFetcher;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.CommandStreams;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.Seed;
import com.sajin.mizan.testkit.SeedExtension;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** M5: depth view correctness and UDP convergence under loss. */
@ExtendWith(SeedExtension.class)
@Timeout(120)
class MarketDataTest {

  static List<RestingOrder> resting(ExecutionCore core) {
    List<RestingOrder> out = new ArrayList<>();
    for (int i : core.instrumentIds()) {
      for (Side s : Side.values()) {
        out.addAll(core.restingOrders(i, s));
      }
    }
    return out;
  }

  static List<DepthBook.Level> engineDepth(ExecutionCore core, int instrument, Side side, int n) {
    List<DepthBook.Level> out = new ArrayList<>();
    for (OrderBook.Level l : core.depth(instrument, side, n)) {
      out.add(new DepthBook.Level(l.price(), l.qty()));
    }
    return out;
  }

  /** M5-T2: the delta-built DepthBook equals the engine's aggregated depth after every command. */
  @Test
  void depthBookEqualsOrderBookDepth(Seed seed) {
    SplittableRandom rnd = seed.random();
    for (int run = 0; run < 100; run++) {
      ExecutionCore core = ExecutionCore.genesis(Fixtures.genesis(), CommandSigner.FAST_VERIFIER);
      DepthBook depth = new DepthBook();
      EngineListener l =
          new EngineListener() {
            @Override
            public void onTrade(com.sajin.mizan.core.Trade t) {}

            @Override
            public void onBookDelta(int instrument, Side side, long price, long aggQty) {
              depth.apply(instrument, side, price, aggQty);
            }
          };
      var gen = new CommandStreams.Generator(CommandSigner.FAST);
      gen.setup().forEach(c -> core.execute(c, l));
      for (int k = 0; k < 150; k++) {
        core.execute(gen.next(CommandStreams.randomOp(rnd), () -> resting(core)), l);
        for (int i : core.instrumentIds()) {
          for (Side s : Side.values()) {
            assertThat(depth.levels(i, s, 0)).isEqualTo(engineDepth(core, i, s, Integer.MAX_VALUE));
          }
        }
      }
    }
  }

  /**
   * M5-T3 gate: through a proxy with 10% loss, 5% duplication and up to 20 ms of reordering delay,
   * the subscriber converges to exactly the engine's top-35 depth.
   */
  @Test
  void subscriberConvergesUnderLossAndReordering(Seed seed) throws Exception {
    SplittableRandom rnd = seed.random();
    ExecutionCore core = ExecutionCore.genesis(Fixtures.genesis(), CommandSigner.FAST_VERIFIER);
    try (MarketDataService md = new MarketDataService(1 << 16, 0, 100, core.instrumentIds());
        LossyUdpProxy proxy = new LossyUdpProxy(md.udpPort(), 0.10, 0.05, 20, rnd.nextLong());
        MarketDataClient client =
            new MarketDataClient(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port()),
                md::snapshot,
                null)) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (md.subscriberCount() == 0 && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertThat(md.subscriberCount()).isEqualTo(1);
      var gen = new CommandStreams.Generator(CommandSigner.FAST);
      gen.setup().forEach(c -> core.execute(c, md));
      for (int k = 0; k < 3_000; k++) {
        SignedCommand c = gen.next(CommandStreams.randomOp(rnd), () -> resting(core));
        core.execute(c, md); // this test thread plays the sequencer
        if (k % 50 == 0) {
          Thread.sleep(2); // spread packets over time so loss hits many of them
        }
      }
      long target = md.publishedSeq();
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      boolean converged = false;
      while (!converged && System.nanoTime() < deadline) {
        Thread.sleep(50);
        converged = client.lastSeq() >= target;
        for (int i : core.instrumentIds()) {
          for (Side s : Side.values()) {
            converged &=
                client
                    .levels(i, s, MdCodec.MAX_LEVELS)
                    .equals(engineDepth(core, i, s, MdCodec.MAX_LEVELS));
          }
        }
      }
      System.out.printf(
          "md convergence: published=%d dropped=%d forwarded=%d gaps=%d snapshots=%d overflow=%d%n",
          target,
          proxy.dropped(),
          proxy.forwarded(),
          client.gapCount(),
          client.snapshotCount(),
          md.overflowCount());
      assertThat(proxy.dropped()).isPositive();
      assertThat(client.gapCount()).isPositive();
      assertThat(md.overflowCount()).isZero();
      assertThat(converged).as("subscriber converged to the exact top-35 depth").isTrue();
    }
  }

  /** The node serves snapshots over TCP and publishes depth over UDP. */
  @Test
  void nodeServesTcpSnapshots() throws Exception {
    try (Node node =
        Node.startInMemory(
            Node.Settings.dev(NodeIntegrationTest.G, 0).withMarketData(0), List.of())) {
      byte[] snap =
          new SnapshotFetcher(
                  new InetSocketAddress(InetAddress.getLoopbackAddress(), node.gatewayPort()))
              .fetch();
      MdCodec.Snapshot s = MdCodec.decodeSnapshot(snap);
      assertThat(s.books()).extracting(MdCodec.Book::instrument).containsExactly(1, 2);
      assertThat(node.marketDataPort()).isPositive();
    }
  }
}
