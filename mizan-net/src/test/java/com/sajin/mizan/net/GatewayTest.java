package com.sajin.mizan.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.CommandSink;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.testkit.SignedCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M4-T2/T3: gateway behaviour against a fake engine. */
@Timeout(30)
class GatewayTest {

  /**
   * Stands in for the sequencer: every submission is "executed" asynchronously and gets a fresh
   * order id. Executing a key twice is exactly the double execution defect 8 is about.
   */
  static final class FakeEngine implements CommandSink {
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final AtomicInteger submissions = new AtomicInteger();
    final AtomicLong ids = new AtomicLong(100);
    final AtomicBoolean full = new AtomicBoolean();
    final List<SignedCommand> executed = new ArrayList<>();
    volatile TcpGateway gateway;
    volatile ExecResult next;

    @Override
    public boolean submit(SignedCommand c) {
      if (full.get()) {
        return false;
      }
      submissions.incrementAndGet();
      exec.execute(
          () -> {
            ExecResult r = next != null ? next : ExecResult.accepted(ids.incrementAndGet());
            synchronized (executed) {
              executed.add(c);
            }
            gateway.onResult(c, r);
          });
      return true;
    }
  }

  FakeEngine engine;
  TcpGateway gw;

  TcpGateway start(TcpGateway.Config cfg, TcpGateway.PreCheck pre) throws Exception {
    engine = new FakeEngine();
    gw = new TcpGateway(engine, pre, new ResultCache(1024, 10_000), cfg);
    engine.gateway = gw;
    gw.start();
    return gw;
  }

  @AfterEach
  void stop() {
    if (gw != null) {
      gw.close();
    }
    if (engine != null) {
      engine.exec.shutdownNow();
    }
  }

  static SignedCommand order(long account, long nonce) {
    return SignedCommands.limit(account, nonce, 1, Side.BUY, 100, 1, TimeInForce.GTC);
  }

  @Test
  void orderIsAckedProvisionallyThenFinal() throws Exception {
    start(TcpGateway.Config.defaults(0), TcpGateway.PreCheck.ACCEPT_ALL);
    try (RawClient c = new RawClient(gw.port())) {
      SignedCommand o = order(2, 1);
      c.send(Messages.order(o.raw()));
      assertThat(c.next()).isEqualTo(new Messages.Accepted(2, 1, 101));
      gw.onFinal(7, List.of(o), List.of(ExecResult.accepted(101)));
      assertThat(c.next()).isEqualTo(new Messages.Final(2, 1, 7, Messages.Final.ACCEPTED, 101));
      c.send(Messages.queryStatus(2, 1));
      assertThat(c.next()).isEqualTo(new Messages.Status(2, 1, Messages.Status.FINAL, 7, 101));
      c.send(Messages.queryStatus(2, 99));
      assertThat(c.next()).isEqualTo(new Messages.Status(2, 99, Messages.Status.UNKNOWN, 0, 0));
    }
  }

  /**
   * Defect 8: a client that never saw its ack retries with the same nonce. It must get the original
   * answer (same order id) and the command must not be executed twice.
   */
  @Test
  void retryAfterLostAckReturnsOriginalResult() throws Exception {
    start(TcpGateway.Config.defaults(0), TcpGateway.PreCheck.ACCEPT_ALL);
    SignedCommand o = order(2, 1);
    try (RawClient first = new RawClient(gw.port())) {
      first.send(Messages.order(o.raw()));
      // The ack is "lost": the connection drops before the client reads it.
      while (gw.cache().get(2, 1) == null) {
        Thread.sleep(5);
      }
    }
    try (RawClient retry = new RawClient(gw.port())) {
      retry.send(Messages.queryStatus(2, 1));
      assertThat(retry.next())
          .isEqualTo(new Messages.Status(2, 1, Messages.Status.PROVISIONAL, 0, 101));
      retry.send(Messages.order(o.raw()));
      assertThat(retry.next()).isEqualTo(new Messages.Accepted(2, 1, 101));
    }
    assertThat(engine.submissions).hasValue(1);
  }

  @Test
  void busyWhenSequencerQueueIsFull() throws Exception {
    start(TcpGateway.Config.defaults(0), TcpGateway.PreCheck.ACCEPT_ALL);
    engine.full.set(true);
    try (RawClient c = new RawClient(gw.port())) {
      c.send(Messages.order(order(2, 1).raw()));
      assertThat(c.next()).isEqualTo(new Messages.Busy(2, 1));
      engine.full.set(false);
      c.send(Messages.order(order(2, 1).raw())); // retry with the same nonce
      assertThat(c.next()).isInstanceOf(Messages.Accepted.class);
    }
  }

  @Test
  void rejectsAndPreCheck() throws Exception {
    start(TcpGateway.Config.defaults(0), c -> c.accountId() != 66);
    try (RawClient c = new RawClient(gw.port())) {
      c.send(Messages.order(order(66, 1).raw()));
      assertThat(c.next())
          .isEqualTo(
              new Messages.Rejected(66, 1, Messages.REASON_PRECHECK, "signature pre-check failed"));
      engine.next = ExecResult.rejected(2, "need more");
      c.send(Messages.order(order(3, 1).raw()));
      assertThat(c.next()).isEqualTo(new Messages.Rejected(3, 1, 2, "need more"));
      assertThat(engine.submissions).hasValue(1);
    }
  }

  @Test
  void authFailedDuplicateGetsTheEarlierOutcome() throws Exception {
    start(TcpGateway.Config.defaults(0), TcpGateway.PreCheck.ACCEPT_ALL);
    SignedCommand o = order(2, 1);
    try (RawClient c = new RawClient(gw.port())) {
      c.send(Messages.order(o.raw()));
      assertThat(c.next()).isEqualTo(new Messages.Accepted(2, 1, 101));
      // A concurrent duplicate that slipped past the cache comes back AUTH_FAILED (stale nonce).
      gw.onResult(o, ExecResult.authFailed(5, "stale"));
      assertThat(gw.cache().get(2, 1).state()).isEqualTo(ResultCache.State.PROVISIONAL);
    }
  }

  @Test
  void tradesGoToOwningAccountsAndRevokesToEveryone() throws Exception {
    start(TcpGateway.Config.defaults(0), TcpGateway.PreCheck.ACCEPT_ALL);
    try (RawClient buyer = new RawClient(gw.port());
        RawClient other = new RawClient(gw.port())) {
      buyer.send(Messages.order(order(2, 1).raw()));
      assertThat(buyer.next()).isInstanceOf(Messages.Accepted.class);
      other.send(Messages.order(order(9, 1).raw()));
      assertThat(other.next()).isInstanceOf(Messages.Accepted.class);
      Trade t = new Trade(1, 1, 5, 6, 2, 3, Side.BUY, 100, 1, 100);
      gw.onTrade(t);
      assertThat(buyer.next()).isEqualTo(new Messages.TradeMsg(t));
      gw.onRevoke(1);
      assertThat(buyer.next()).isEqualTo(new Messages.Revoke(1));
      assertThat(other.next()).isEqualTo(new Messages.Revoke(1)); // no TRADE for account 9
    }
  }

  @Test
  void snapshotRequestUsesProvider() throws Exception {
    start(TcpGateway.Config.defaults(0), TcpGateway.PreCheck.ACCEPT_ALL);
    gw.setSnapshotProvider(() -> new byte[] {4, 2});
    try (RawClient c = new RawClient(gw.port())) {
      c.send(FrameCodec.encode(FrameCodec.SNAPSHOT_REQ, new byte[0]));
      assertThat(((Messages.Snapshot) c.next()).payload()).containsExactly(4, 2);
    }
  }
}
