package com.sajin.mizan.net;

import com.sajin.mizan.chain.bft.Network;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validator-to-validator transport over TCP (M7-T7). One persistent outbound connection per peer,
 * reconnecting with exponential backoff (100 ms → 2 s); one inbound listener. Each message is a
 * {@code PEER} frame with payload {@code [from:4][message]}. Outbound queues are bounded and drop
 * the oldest message when a peer is unreachable: consensus retransmits, so loss is tolerated.
 *
 * <p><b>Peer authentication (M9-T3).</b> The acceptor sends a fresh 32-byte challenge; the dialer
 * answers {@code PEER_HELLO [from:4][sig]} with {@code sig} over {@code "MZPEER1" || to || from ||
 * challenge} made with its validator key. Only then is the connection bound to {@code from}, and
 * every later frame must carry that same {@code from}. Without this, anyone who could reach a peer
 * port could speak as any validator for the unsigned messages (sync, gossip) and, for example, get
 * an honest validator banned from sync. Links are still plaintext (no TLS; see the threat model).
 *
 * <p>Inbound messages are handed to {@code inbox} on network threads; the consumer must hand them
 * over to the consensus event loop.
 */
public final class TcpNetwork implements Network, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(TcpNetwork.class);
  private static final int QUEUE = 4_096;
  private static final int HANDSHAKE_TIMEOUT_MS = 5_000;
  private static final byte[] DOMAIN = "MZPEER1".getBytes(StandardCharsets.US_ASCII);

  /** Signs this validator's handshakes and verifies other validators'. */
  public interface PeerAuth {
    byte[] sign(byte[] message);

    boolean verify(int peer, byte[] message, byte[] signature);

    /** No authentication: accepts any claimed identity. Tests of the transport only. */
    PeerAuth NONE =
        new PeerAuth() {
          @Override
          public byte[] sign(byte[] message) {
            return new byte[0];
          }

          @Override
          public boolean verify(int peer, byte[] message, byte[] signature) {
            return true;
          }
        };
  }

  private final PeerAuth auth;
  private final SecureRandom random = new SecureRandom();

  private final int me;
  private final List<InetSocketAddress> peers;
  private final BiConsumer<Integer, byte[]> inbox;
  private final List<LinkedBlockingDeque<byte[]>> outbound = new ArrayList<>();
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final List<Thread> threads = new ArrayList<>();
  private volatile boolean running = true;
  private ServerSocket server;

  public TcpNetwork(
      int me, List<InetSocketAddress> peers, PeerAuth auth, BiConsumer<Integer, byte[]> inbox) {
    this.me = me;
    this.auth = auth;
    this.peers = List.copyOf(peers);
    this.inbox = inbox;
    for (int i = 0; i < peers.size(); i++) {
      outbound.add(new LinkedBlockingDeque<>(QUEUE));
    }
  }

  public void start() throws IOException {
    server = new ServerSocket();
    server.setReuseAddress(true);
    server.bind(peers.get(me));
    threads.add(Thread.ofPlatform().name("peer-accept-" + me).daemon(true).start(this::acceptLoop));
    for (int i = 0; i < peers.size(); i++) {
      if (i != me) {
        int peer = i;
        threads.add(
            Thread.ofVirtual().name("peer-out-" + me + "-" + peer).start(() -> sendLoop(peer)));
      }
    }
  }

  @Override
  public void send(int to, byte[] message) {
    if (to == me || to < 0 || to >= peers.size()) {
      return;
    }
    byte[] frame =
        FrameCodec.encode(
            FrameCodec.PEER,
            ByteBuffer.allocate(4 + message.length).putInt(me).put(message).array());
    LinkedBlockingDeque<byte[]> q = outbound.get(to);
    while (!q.offerLast(frame)) {
      q.pollFirst(); // peer unreachable for a while: drop the oldest
    }
  }

  @Override
  public void broadcast(byte[] message) {
    for (int i = 0; i < peers.size(); i++) {
      if (i != me) {
        send(i, message);
      }
    }
  }

  private void sendLoop(int peer) {
    long backoff = 100;
    boolean connected = false;
    LinkedBlockingDeque<byte[]> q = outbound.get(peer);
    while (running) {
      try (Socket s = new Socket()) {
        s.connect(peers.get(peer), 2_000);
        s.setTcpNoDelay(true);
        sockets.add(s);
        OutputStream out = new BufferedOutputStream(s.getOutputStream());
        answerChallenge(s, peer, out);
        backoff = 100;
        connected = true;
        LOG.atInfo()
            .setMessage("peer_link_up")
            .addKeyValue("me", me)
            .addKeyValue("peer", peer)
            .log();
        while (running) {
          byte[] frame = q.pollFirst(500, TimeUnit.MILLISECONDS);
          if (frame == null) {
            continue;
          }
          out.write(frame);
          if (q.isEmpty()) {
            out.flush();
          }
        }
      } catch (IOException e) {
        if (connected && running) {
          LOG.atInfo()
              .setMessage("peer_link_down")
              .addKeyValue("me", me)
              .addKeyValue("peer", peer)
              .addKeyValue("error", e.toString())
              .log();
        }
        connected = false;
        if (running) {
          sleep(backoff);
          backoff = Math.min(2_000, backoff * 2);
        }
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket s = server.accept();
        sockets.add(s);
        threads.add(Thread.ofVirtual().name("peer-in-" + me).start(() -> readLoop(s)));
      } catch (IOException e) {
        if (running) {
          LOG.atWarn().setMessage("peer_accept_failed").addKeyValue("error", e.toString()).log();
        }
      }
    }
  }

  /** Dialer side: read the acceptor's challenge and prove we are validator {@code me}. */
  private void answerChallenge(Socket s, int peer, OutputStream out) throws IOException {
    s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
    FrameCodec.Frame c = FrameCodec.read(new BufferedInputStream(s.getInputStream()));
    if (c == null || c.type() != FrameCodec.PEER_CHALLENGE || c.payload().length != 32) {
      throw new IOException("peer " + peer + " sent no valid challenge");
    }
    byte[] sig = auth.sign(transcript(peer, me, c.payload()));
    out.write(
        FrameCodec.encode(
            FrameCodec.PEER_HELLO,
            ByteBuffer.allocate(4 + sig.length).putInt(me).put(sig).array()));
    out.flush();
    s.setSoTimeout(0);
  }

  private static byte[] transcript(int to, int from, byte[] challenge) {
    return ByteBuffer.allocate(DOMAIN.length + 8 + challenge.length)
        .put(DOMAIN)
        .putInt(to)
        .putInt(from)
        .put(challenge)
        .array();
  }

  /**
   * Acceptor side: challenge the dialer and verify its hello. Returns the authenticated peer index,
   * or {@code -1} to drop the connection.
   */
  private int authenticate(Socket s, InputStream in) throws IOException {
    byte[] challenge = new byte[32];
    random.nextBytes(challenge);
    OutputStream out = s.getOutputStream();
    out.write(FrameCodec.encode(FrameCodec.PEER_CHALLENGE, challenge));
    out.flush();
    s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
    FrameCodec.Frame h = FrameCodec.read(in);
    s.setSoTimeout(0);
    if (h == null || h.type() != FrameCodec.PEER_HELLO || h.payload().length < 4) {
      return -1;
    }
    ByteBuffer b = ByteBuffer.wrap(h.payload());
    int from = b.getInt();
    byte[] sig = new byte[b.remaining()];
    b.get(sig);
    if (from < 0 || from >= peers.size() || from == me) {
      return -1;
    }
    if (!auth.verify(from, transcript(me, from, challenge), sig)) {
      LOG.atWarn()
          .setMessage("peer_auth_failed")
          .addKeyValue("me", me)
          .addKeyValue("claimed", from)
          .addKeyValue("remote", String.valueOf(s.getRemoteSocketAddress()))
          .log();
      return -1;
    }
    return from;
  }

  private void readLoop(Socket s) {
    try (s) {
      InputStream in = new BufferedInputStream(s.getInputStream());
      int peer = authenticate(s, in);
      if (peer < 0) {
        return;
      }
      FrameCodec.Frame f;
      while (running && (f = FrameCodec.read(in)) != null) {
        if (f.type() != FrameCodec.PEER || f.payload().length < 4) {
          return; // not a peer: drop the connection
        }
        ByteBuffer b = ByteBuffer.wrap(f.payload());
        int from = b.getInt();
        if (from != peer) {
          return; // a frame claiming another identity: drop the connection
        }
        byte[] msg = new byte[b.remaining()];
        b.get(msg);
        inbox.accept(from, msg);
      }
    } catch (IOException e) {
      // peer went away; it reconnects
    } finally {
      sockets.remove(s);
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    running = false;
    try {
      if (server != null) {
        server.close();
      }
    } catch (IOException ignored) {
      // closing
    }
    for (Socket s : sockets) {
      try {
        s.close();
      } catch (IOException ignored) {
        // closing
      }
    }
    for (Thread t : threads) {
      t.interrupt();
    }
  }
}
