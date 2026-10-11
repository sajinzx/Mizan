package com.sajin.mizan.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.testkit.SignedCommands;
import java.nio.ByteBuffer;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M4-T4: hostile clients are cut off and the server keeps serving others. */
@Timeout(60)
class HardeningTest {
  GatewayTest.FakeEngine engine;
  TcpGateway gw;

  void start(TcpGateway.Config cfg) throws Exception {
    engine = new GatewayTest.FakeEngine();
    gw = new TcpGateway(engine, TcpGateway.PreCheck.ACCEPT_ALL, new ResultCache(64, 1_000), cfg);
    engine.gateway = gw;
    gw.start();
  }

  @AfterEach
  void stop() {
    gw.close();
    engine.exec.shutdownNow();
  }

  /** A healthy client still gets served. */
  void assertServerHealthy() throws Exception {
    try (RawClient ok = new RawClient(gw.port())) {
      ok.send(Messages.queryStatus(1, 1));
      assertThat(ok.next()).isInstanceOf(Messages.Status.class);
    }
  }

  @Test
  void slowLorisIsDisconnectedByTheFrameDeadline() throws Exception {
    start(TcpGateway.Config.defaults(0).withTimeouts(60_000, 1_000));
    byte[] frame = Messages.order(GatewayTest.order(2, 1).raw());
    try (RawClient loris = new RawClient(gw.port())) {
      long start = System.nanoTime();
      boolean cut = false;
      for (int i = 0; i < frame.length - 1; i++) {
        try {
          loris.send(new byte[] {frame[i]});
        } catch (java.io.IOException e) {
          cut = true;
          break;
        }
        Thread.sleep(300);
        if (System.nanoTime() - start > 4_000_000_000L) {
          break;
        }
      }
      assertThat(cut || loris.closedByServer()).isTrue();
      assertThat(System.nanoTime() - start).isLessThan(10_000_000_000L);
    }
    assertServerHealthy();
  }

  @Test
  void halfFrameThenSilenceIsDisconnected() throws Exception {
    start(TcpGateway.Config.defaults(0).withTimeouts(60_000, 500));
    try (RawClient c = new RawClient(gw.port())) {
      c.send(new byte[] {0, 0, 0, 20, 1, 1, 1});
      assertThat(c.next()).isEqualTo(new Messages.Err(Messages.ERR_TIMEOUT));
      assertThat(c.closedByServer()).isTrue();
    }
    assertServerHealthy();
  }

  @Test
  void idleConnectionIsDisconnected() throws Exception {
    start(TcpGateway.Config.defaults(0).withTimeouts(500, 500));
    try (RawClient c = new RawClient(gw.port())) {
      assertThat(c.next()).isEqualTo(new Messages.Err(Messages.ERR_TIMEOUT));
      assertThat(c.closedByServer()).isTrue();
    }
  }

  @Test
  void oversizedFrameIsRejected() throws Exception {
    start(TcpGateway.Config.defaults(0));
    try (RawClient c = new RawClient(gw.port())) {
      c.send(ByteBuffer.allocate(5).putInt(FrameCodec.MAX_FRAME + 1).put((byte) 1).array());
      assertThat(c.next()).isEqualTo(new Messages.Err(Messages.ERR_BAD_FRAME));
      assertThat(c.closedByServer()).isTrue();
    }
    assertServerHealthy();
  }

  @Test
  void garbageIsRejectedAndServerSurvives() throws Exception {
    start(TcpGateway.Config.defaults(0).withTimeouts(60_000, 500));
    SplittableRandom rnd = new SplittableRandom(7);
    for (int i = 0; i < 20; i++) {
      byte[] junk = new byte[rnd.nextInt(1, 200)];
      rnd.nextBytes(junk);
      try (RawClient c = new RawClient(gw.port())) {
        c.send(junk);
        assertThat(c.closedByServer()).as("junk %d", i).isTrue();
      } catch (java.net.SocketException e) {
        // reset while sending: also a disconnect
      }
    }
    assertServerHealthy();
    assertThat(engine.submissions).hasValue(0);
  }

  @Test
  void repeatedBadEnvelopesDisconnect() throws Exception {
    start(TcpGateway.Config.defaults(0));
    try (RawClient c = new RawClient(gw.port())) {
      byte[] bad = Messages.order(new byte[] {1, 2, 3});
      c.send(bad);
      assertThat(c.next()).isEqualTo(new Messages.Err(Messages.ERR_BAD_ENVELOPE));
      c.send(bad);
      assertThat(c.next()).isEqualTo(new Messages.Err(Messages.ERR_BAD_ENVELOPE));
      c.send(bad);
      assertThat(c.next()).isEqualTo(new Messages.Err(Messages.ERR_BAD_ENVELOPE));
      assertThat(c.closedByServer()).isTrue();
    }
  }

  @Test
  void connectionLimit() throws Exception {
    start(TcpGateway.Config.defaults(0).withMaxConnections(2));
    try (RawClient a = new RawClient(gw.port());
        RawClient b = new RawClient(gw.port());
        RawClient c = new RawClient(gw.port())) {
      assertThat(c.next()).isEqualTo(new Messages.Err(Messages.ERR_TOO_MANY_CONNECTIONS));
      a.send(Messages.queryStatus(1, 1));
      assertThat(a.next()).isInstanceOf(Messages.Status.class);
      b.send(Messages.queryStatus(1, 1));
      assertThat(b.next()).isInstanceOf(Messages.Status.class);
    }
    Thread.sleep(200); // slots are released when the server notices the closes
    assertServerHealthy();
  }

  @Test
  void rateLimitAnswersBusy() throws Exception {
    start(TcpGateway.Config.defaults(0).withLimits(1, 3));
    try (RawClient c = new RawClient(gw.port())) {
      int busy = 0;
      for (long n = 1; n <= 6; n++) {
        c.send(
            Messages.order(SignedCommands.limit(2, n, 1, Side.BUY, 100, 1, TimeInForce.GTC).raw()));
        if (c.next() instanceof Messages.Busy) {
          busy++;
        }
      }
      assertThat(busy).isEqualTo(3);
      assertThat(engine.submissions).hasValue(3);
    }
  }
}
