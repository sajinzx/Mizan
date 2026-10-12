package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.sim.MizanClient;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * M8-T2/T3/T6/T7 over real TCP: four validators with a rotating proposer. Traders are spread over
 * all gateways. One validator is killed mid-run; its traders fail over to another validator and
 * retry with the same nonces. The killed validator later restarts and catches up. Gate M8:
 * client-observed FINAL results equal the chain's, and every replica has the same state.
 */
@Timeout(300)
class RotatingClusterTest {
  @TempDir Path dir;

  record Final(long height, long orderId) {}

  @Test
  void failoverAndCatchUp() throws Exception {
    GenesisConfig g = BftClusterTest.genesisOnFreePorts(4);
    AtomicReferenceArray<ValidatorNode> nodes = new AtomicReferenceArray<>(4);
    for (int i = 0; i < 4; i++) {
      nodes.set(i, start(g, i));
    }
    Map<String, Final> seen = new ConcurrentHashMap<>();
    try {
      // Register and fund traders through validator 0; wait until final everywhere.
      int traders = 8;
      try (MizanClient admin = new MizanClient("127.0.0.1", nodes.get(0).gatewayPort())) {
        long adminNonce = 1;
        for (int t = 0; t < traders; t++) {
          long acct = 500L + t;
          finalOf(
              admin,
              CommandSigner.ED25519.sign(
                  Fixtures.CHAIN_ID,
                  acct,
                  1,
                  new Register(Fixtures.accountSigner(acct).publicKey())));
          finalOf(
              admin,
              CommandSigner.ED25519.sign(
                  Fixtures.CHAIN_ID,
                  Accounts.ADMIN_ACCOUNT,
                  adminNonce++,
                  new Deposit(acct, Fixtures.QUOTE, 1_000_000)));
          finalOf(
              admin,
              CommandSigner.ED25519.sign(
                  Fixtures.CHAIN_ID,
                  Accounts.ADMIN_ACCOUNT,
                  adminNonce++,
                  new Deposit(acct, Fixtures.BASE, 10_000)));
        }
      }
      List<Thread> threads = new ArrayList<>();
      List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
      for (int t = 0; t < traders; t++) {
        int trader = t;
        threads.add(
            Thread.ofPlatform()
                .start(
                    () -> {
                      try {
                        trade(nodes, trader, 500L + trader, 20, seen);
                      } catch (Throwable e) {
                        errors.add(e);
                      }
                    }));
      }
      Thread.sleep(1_500);
      nodes.get(1).close(); // crash validator 1 mid-run; its clients fail over
      for (Thread th : threads) {
        th.join();
      }
      assertThat(errors).isEmpty();
      // Restart validator 1 from its data dir: it must catch up by sync.
      long target = Math.max(nodes.get(0).height(), nodes.get(2).height());
      nodes.set(1, start(g, 1));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (nodes.get(1).height() < target && System.nanoTime() < deadline) {
        Thread.sleep(100);
      }
      assertThat(nodes.get(1).height())
          .as("restarted validator caught up")
          .isGreaterThanOrEqualTo(target);
      // Let everyone settle on a common height (empty blocks keep heights moving).
      long common = 0;
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
          min = Math.min(min, nodes.get(i).height());
          assertThat(nodes.get(i).failure()).isNull();
        }
        if (min > target + 2) {
          common = min;
          break;
        }
        Thread.sleep(100);
      }
      assertThat(common).isPositive();

      // M9-T6 chain panel data: every validator is seen live, heights and roots agree.
      Messages.ChainInfo info =
          new com.sajin.mizan.net.ChainInfoFetcher(
                  new java.net.InetSocketAddress(
                      java.net.InetAddress.getLoopbackAddress(), nodes.get(0).gatewayPort()))
              .fetch();
      assertThat(info.height()).isGreaterThanOrEqualTo(common);
      assertThat(info.validators()).hasSize(4);
      for (Messages.ChainInfo.Validator v : info.validators()) {
        assertThat(v.ageMillis())
            .as("validator %d heard recently", v.index())
            .isBetween(0L, 3_000L);
        assertThat(v.height()).as("validator %d height known", v.index()).isPositive();
      }
    } finally {
      for (int i = 0; i < 4; i++) {
        nodes.get(i).close();
      }
    }

    // Every replica agrees on every block it has; the chain explains every client FINAL.
    Map<String, Final> chain = new HashMap<>();
    List<byte[]> heads = new ArrayList<>();
    long minHeight = Long.MAX_VALUE;
    for (int i = 0; i < 4; i++) {
      minHeight = Math.min(minHeight, nodes.get(i).height());
    }
    for (int i = 0; i < 4; i++) {
      try (BlockStore s = BlockStore.open(dir.resolve("v" + i).resolve("blocks.log"))) {
        ExecutionCore replay = ExecutionCore.genesis(g, CommandSigner.FAST_VERIFIER, false);
        for (int k = 0; k < s.size(); k++) {
          CommittedBlock cb = CommittedBlock.decode(s.read(k));
          for (SignedCommand c : cb.block().commands()) {
            ExecResult r = replay.execute(c, ExecutionCore.SILENT);
            String key = c.accountId() + "/" + c.nonce();
            Final f = new Final(cb.height(), r.orderId());
            Final prev = chain.putIfAbsent(key, f);
            assertThat(prev == null || prev.equals(f)).as("replicas disagree on %s", key).isTrue();
          }
          if (cb.height() == minHeight) {
            heads.add(cb.block().hash());
          }
        }
      }
    }
    assertThat(heads).hasSize(4);
    for (byte[] h : heads) {
      assertThat(h).isEqualTo(heads.get(0));
    }
    assertThat(seen).isNotEmpty();
    for (Map.Entry<String, Final> e : seen.entrySet()) {
      assertThat(chain.get(e.getKey())).as("client FINAL %s", e.getKey()).isEqualTo(e.getValue());
    }
    System.out.printf(
        "rotating cluster: %d client FINALs match the chain; common height >= %d%n",
        seen.size(), minHeight);
  }

  ValidatorNode start(GenesisConfig g, int i) throws IOException {
    return ValidatorNode.start(
        Node.Settings.dev(g, 0),
        Node.Persistence.at(dir.resolve("v" + i)).withSnapshotEvery(10),
        Fixtures.validatorSigner(i),
        i,
        List.of());
  }

  static Messages.Final finalOf(MizanClient c, SignedCommand cmd) throws Exception {
    Messages.ServerMessage m = c.submit(cmd, Duration.ofSeconds(30));
    if (m instanceof Messages.Final f) {
      return f;
    }
    return c.finality(cmd.accountId(), cmd.nonce()).get(30, TimeUnit.SECONDS);
  }

  /** One trader: orders through validator {@code trader % 4}, failing over on connection loss. */
  static void trade(
      AtomicReferenceArray<ValidatorNode> nodes,
      int trader,
      long account,
      int orders,
      Map<String, Final> seen)
      throws Exception {
    SplittableRandom rnd = new SplittableRandom(trader);
    int at = trader % 4;
    MizanClient c = new MizanClient("127.0.0.1", nodes.get(at).gatewayPort());
    try {
      for (int k = 0; k < orders; k++) {
        long nonce = 2L + k;
        SignedCommand cmd =
            CommandSigner.ED25519.sign(
                Fixtures.CHAIN_ID,
                account,
                nonce,
                new Place(
                    k,
                    Fixtures.INSTRUMENT,
                    rnd.nextBoolean() ? Side.BUY : Side.SELL,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    98L + rnd.nextInt(5),
                    1L + rnd.nextInt(3)));
        Messages.Final f = null;
        for (int attempt = 0; f == null && attempt < 10; attempt++) {
          try {
            f = finalOf(c, cmd);
          } catch (Exception e) {
            c.close();
            // Fail over to the next reachable validator; retry with the same nonce.
            for (int hop = 1; hop <= 4; hop++) {
              int next = (at + hop) % 4;
              try {
                c = connect(nodes, next);
                at = next;
                break;
              } catch (IOException unreachable) {
                // try the next one
              }
            }
          }
        }
        assertThat(f).as("trader %d order %d", trader, k).isNotNull();
        seen.put(account + "/" + nonce, new Final(f.height(), f.orderId()));
      }
    } finally {
      c.close();
    }
  }

  static MizanClient connect(AtomicReferenceArray<ValidatorNode> nodes, int at) throws IOException {
    return new MizanClient("127.0.0.1", nodes.get(at).gatewayPort());
  }
}
