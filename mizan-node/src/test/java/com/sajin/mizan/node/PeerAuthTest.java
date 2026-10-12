package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.net.FrameCodec;
import com.sajin.mizan.net.TcpNetwork;
import com.sajin.mizan.testkit.Fixtures;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M9-T3: validator links are authenticated with validator keys; impostors are dropped. */
@Timeout(60)
class PeerAuthTest {
  record Delivery(int from, String text) {}

  @Test
  void onlyKeyHoldersCanSpeakForAValidator() throws Exception {
    GenesisConfig g = BftClusterTest.genesisOnFreePorts(3);
    List<InetSocketAddress> peers = BftSetup.peers(g);
    List<Delivery> at0 = new CopyOnWriteArrayList<>();
    try (TcpNetwork v0 =
            new TcpNetwork(
                0,
                peers,
                BftSetup.peerAuth(g, Fixtures.validatorSigner(0)),
                (from, msg) ->
                    at0.add(new Delivery(from, new String(msg, StandardCharsets.UTF_8))));
        TcpNetwork v1 =
            new TcpNetwork(
                1, peers, BftSetup.peerAuth(g, Fixtures.validatorSigner(1)), (f, m) -> {});
        // Claims to be validator 2 but holds validator 1's key.
        TcpNetwork fake2 =
            new TcpNetwork(
                2, peers, BftSetup.peerAuth(g, Fixtures.validatorSigner(1)), (f, m) -> {})) {
      v0.start();
      v1.start();
      fake2.start();
      v1.send(0, "hello from 1".getBytes(StandardCharsets.UTF_8));
      fake2.send(0, "forged 2".getBytes(StandardCharsets.UTF_8));

      // A raw attacker: answers the challenge with a junk signature, then sends a peer frame.
      try (Socket s = new Socket()) {
        s.connect(peers.get(0), 2_000);
        s.setSoTimeout(5_000);
        InputStream in = new BufferedInputStream(s.getInputStream());
        FrameCodec.Frame challenge = FrameCodec.read(in);
        assertThat(challenge.type()).isEqualTo(FrameCodec.PEER_CHALLENGE);
        OutputStream out = s.getOutputStream();
        out.write(
            FrameCodec.encode(FrameCodec.PEER_HELLO, ByteBuffer.allocate(68).putInt(1).array()));
        out.write(
            FrameCodec.encode(
                FrameCodec.PEER,
                ByteBuffer.allocate(4 + 8)
                    .putInt(1)
                    .put("attacker".getBytes(StandardCharsets.UTF_8))
                    .array()));
        out.flush();
        assertThat(dropped(in)).as("connection dropped").isTrue();
      }

      long deadline = System.nanoTime() + 10_000_000_000L;
      while (at0.isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      Thread.sleep(1_000); // give the forged link time to (not) deliver
      assertThat(at0).containsExactly(new Delivery(1, "hello from 1"));
    }
  }

  /** EOF, or a reset (Windows resets when unread bytes are discarded on close). */
  static boolean dropped(InputStream in) {
    try {
      return in.read() == -1;
    } catch (java.net.SocketException reset) {
      return true;
    } catch (java.io.IOException e) {
      return false;
    }
  }
}
