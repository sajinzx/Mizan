package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.chain.bft.RoundBft;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.sim.LoadGenerator;
import com.sajin.mizan.sim.MizanClient;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.SignedCommands;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** M7-T7/T8: four validators over localhost TCP agree on every block (smoke test, Gate M7). */
@Timeout(240)
class BftClusterTest {
  @TempDir Path dir;

  /** The fixture genesis for {@code n} validators, re-addressed to free localhost ports. */
  static GenesisConfig genesisOnFreePorts(int n) throws IOException {
    GenesisConfig g = Fixtures.genesis(n);
    List<GenesisConfig.Validator> vs = new ArrayList<>();
    for (GenesisConfig.Validator v : g.validators()) {
      vs.add(new GenesisConfig.Validator(v.index(), v.pubKey(), "127.0.0.1", freeTcpAndUdpPort()));
    }
    return new GenesisConfig(
        g.chainId(),
        g.stateRootVersion(),
        g.adminPubKey(),
        g.assets(),
        g.instruments(),
        vs,
        g.block(),
        g.consensus());
  }

  /** A port number free for both TCP (peer links) and UDP (heartbeats). */
  static int freeTcpAndUdpPort() throws IOException {
    for (int attempt = 0; attempt < 50; attempt++) {
      try (ServerSocket s = new ServerSocket(0)) {
        int port = s.getLocalPort();
        try (java.net.DatagramSocket d = new java.net.DatagramSocket(port)) {
          return d.getLocalPort();
        } catch (java.net.SocketException taken) {
          // UDP side busy: try another
        }
      }
    }
    throw new IOException("no free TCP+UDP port");
  }

  @Test
  void fourValidatorsOverTcpAgree() throws Exception {
    GenesisConfig g = genesisOnFreePorts(4);
    RoundBft.ProposerFn fixed = RoundBft.ProposerFn.fixed(0);
    List<FollowerValidator> followers = new ArrayList<>();
    Node proposer =
        Node.startBft(
            Node.Settings.dev(g, 0),
            Node.Persistence.at(dir.resolve("v0")).withSnapshotEvery(10),
            Fixtures.validatorSigner(0),
            0,
            fixed,
            List.of(),
            List.of());
    try {
      for (int i = 1; i < 4; i++) {
        followers.add(
            FollowerValidator.start(
                g,
                Node.Persistence.at(dir.resolve("v" + i)).withSnapshotEvery(10),
                Fixtures.validatorSigner(i),
                i,
                fixed));
      }
      var p = LoadGenerator.Params.of("127.0.0.1", proposer.gatewayPort(), 4, 30, 100, 5);
      LoadGenerator.setup(p);
      try (MizanClient c = new MizanClient("127.0.0.1", proposer.gatewayPort())) {
        SignedCommand ask = SignedCommands.limit(100, 2, 1, Side.SELL, 100, 3, TimeInForce.GTC);
        assertThat(c.submit(ask, Duration.ofSeconds(30))).isInstanceOf(Messages.Accepted.class);
        Messages.Final f = c.finality(100, 2).get(60, TimeUnit.SECONDS);
        assertThat(f.status()).isEqualTo(Messages.Final.ACCEPTED);
      }
      LoadGenerator.Result r = LoadGenerator.run(p);
      assertThat(r.errors()).isZero();
      // Let empty blocks carry everyone to a common, stable height.
      long target = proposer.height() + 3;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (System.nanoTime() < deadline) {
        long min = proposer.height();
        for (FollowerValidator fv : followers) {
          min = Math.min(min, fv.height());
          assertThat(fv.failure()).isNull();
        }
        if (min >= target) {
          break;
        }
        Thread.sleep(100);
      }
    } finally {
      proposer.close();
      followers.forEach(FollowerValidator::close);
    }
    long h = proposer.height();
    System.out.printf(
        "bft smoke: proposer height %d, follower heights %s%n",
        h, followers.stream().map(FollowerValidator::height).toList());
    for (FollowerValidator fv : followers) {
      assertThat(fv.height()).isGreaterThan(5);
      if (fv.height() == h) {
        assertThat(fv.committedCore().stateRoot()).isEqualTo(proposer.committedCore().stateRoot());
      }
    }
    NodeIntegrationTest.assertConservation(proposer.committedCore());
  }
}
