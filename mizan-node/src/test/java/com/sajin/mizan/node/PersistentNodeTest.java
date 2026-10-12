package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.sim.LoadGenerator;
import com.sajin.mizan.sim.MizanClient;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.RecordingListener;
import com.sajin.mizan.testkit.SignedCommands;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** M6-T5/T7/T8/T9: persistence, recovery, FINAL, rollback. */
@Timeout(180)
class PersistentNodeTest {
  static final GenesisConfig G = DevGenesis.config();
  @TempDir Path dir;

  Node start(Node.Persistence p, List<com.sajin.mizan.core.EngineListener> listeners)
      throws Exception {
    return Node.startPersistent(
        Node.Settings.dev(G, 0), p, Fixtures.validatorSigner(0), listeners, List.of());
  }

  @Test
  void restartRecoversTheSameStateAndFinalResults() throws Exception {
    Node.Persistence p = Node.Persistence.at(dir).withSnapshotEvery(3);
    SignedCommand ask = SignedCommands.limit(60, 2, 1, Side.SELL, 100, 5, TimeInForce.GTC);
    byte[] rootBefore;
    long heightBefore;
    Messages.Final askFinal;
    Node node = start(p, List.of());
    try {
      var lp = LoadGenerator.Params.of("127.0.0.1", node.gatewayPort(), 5, 40, 60, 3);
      LoadGenerator.setup(lp);
      try (MizanClient c = new MizanClient("127.0.0.1", node.gatewayPort())) {
        assertThat(c.submit(ask, Duration.ofSeconds(10))).isInstanceOf(Messages.Accepted.class);
        askFinal = c.finality(60, 2).get(10, TimeUnit.SECONDS);
      }
      LoadGenerator.Result r = LoadGenerator.run(lp);
      assertThat(r.errors()).isZero();
      NodeIntegrationTest.awaitHeightStable(node);
    } finally {
      node.close();
    }
    rootBefore = node.committedCore().stateRoot();
    heightBefore = node.height();
    assertThat(heightBefore).isGreaterThan(3);
    try (var files = Files.list(dir.resolve("snapshots"))) {
      assertThat(files.count()).isBetween(1L, 2L);
    }

    Node again = start(p, List.of());
    try {
      assertThat(again.height()).isEqualTo(heightBefore);
      try (MizanClient c = new MizanClient("127.0.0.1", again.gatewayPort())) {
        // The command is final; a retry after restart answers from the rebuilt cache or the chain.
        // Only the blocks replayed after the newest snapshot rebuild the cache, so the answer is
        // FINAL (with the original height and order id) or UNKNOWN, never provisional.
        Messages.Status st = c.queryStatus(60, 2).get(5, TimeUnit.SECONDS);
        assertThat(st.state()).isIn(Messages.Status.FINAL, Messages.Status.UNKNOWN);
        if (st.state() == Messages.Status.FINAL) {
          assertThat(st.height()).isEqualTo(askFinal.height());
          assertThat(st.orderId()).isEqualTo(askFinal.orderId());
        }
        Messages.ServerMessage retry = c.submit(ask, Duration.ofSeconds(10));
        assertThat(retry).isNotInstanceOf(Messages.Accepted.class); // never executed twice
      }
    } finally {
      again.close();
    }
    ExecutionCore recovered = again.committedCore();
    assertThat(recovered.stateRoot()).isEqualTo(rootBefore);
    NodeIntegrationTest.assertConservation(recovered);
  }

  @Test
  void refusesADataDirFromAnotherGenesis() throws Exception {
    start(Node.Persistence.at(dir), List.of()).close();
    GenesisConfig other =
        GenesisJson.parse(
            GenesisJson.toJson(G).replace("\"chainId\" : 4242", "\"chainId\" : 4243"));
    assertThatThrownBy(
            () ->
                Node.startPersistent(
                    Node.Settings.dev(other, 0),
                    Node.Persistence.at(dir),
                    Fixtures.validatorSigner(0),
                    List.of(),
                    List.of()))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("different genesis");
  }

  /**
   * Defect 6: when a draft fails, REVOKE must start at the first trade of the oldest uncommitted
   * draft, not at 0. Everything uncommitted is replayed and eventually committed.
   */
  @Test
  void revokeStartsAtOldestUncommittedTrade() throws Exception {
    AtomicReference<Sequencer.BlockDraft> failed = new AtomicReference<>();
    RecordingListener events = new RecordingListener();
    List<Object> seen = new CopyOnWriteArrayList<>();
    com.sajin.mizan.core.EngineListener spy =
        new com.sajin.mizan.core.EngineListener() {
          @Override
          public void onTrade(Trade t) {
            seen.add(t);
          }

          @Override
          public void onRevoke(long fromTradeSeq) {
            seen.add(new RecordingListener.Revoked(fromTradeSeq));
          }
        };
    Node.Persistence p =
        Node.Persistence.at(dir)
            .withFailHook(d -> d.firstTradeSeq() > 1 && failed.compareAndSet(null, d));
    Node node = start(p, List.of(spy, events));
    try {
      var lp = LoadGenerator.Params.of("127.0.0.1", node.gatewayPort(), 6, 60, 80, 9);
      LoadGenerator.setup(lp);
      assertThat(LoadGenerator.run(lp).errors()).isZero();
      NodeIntegrationTest.awaitHeightStable(node);
    } finally {
      node.close();
    }
    assertThat(failed.get()).as("a draft after the first trade was failed").isNotNull();
    List<Object> revokes =
        seen.stream().filter(e -> e instanceof RecordingListener.Revoked).toList();
    assertThat(revokes)
        .containsExactly(new RecordingListener.Revoked(failed.get().firstTradeSeq()));
    assertThat(failed.get().firstTradeSeq()).isGreaterThan(1);
    // The failed draft's commands were replayed and committed: the committed state equals a
    // replay of the block log, and conservation holds.
    NodeIntegrationTest.assertConservation(node.committedCore());
  }
}
