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
 * <p>Inbound messages are handed to {@code inbox} on network threads; the consumer must hand them
 * over to the consensus event loop.
 */
public final class TcpNetwork implements Network, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(TcpNetwork.class);
  private static final int QUEUE = 4_096;

  private final int me;
  private final List<InetSocketAddress> peers;
  private final BiConsumer<Integer, byte[]> inbox;
  private final List<LinkedBlockingDeque<byte[]>> outbound = new ArrayList<>();
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final List<Thread> threads = new ArrayList<>();
  private volatile boolean running = true;
  private ServerSocket server;

  public TcpNetwork(int me, List<InetSocketAddress> peers, BiConsumer<Integer, byte[]> inbox) {
    this.me = me;
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
        backoff = 100;
        connected = true;
        LOG.atInfo()
            .setMessage("peer_link_up")
            .addKeyValue("me", me)
            .addKeyValue("peer", peer)
            .log();
        OutputStream out = new BufferedOutputStream(s.getOutputStream());
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

  private void readLoop(Socket s) {
    try (s) {
      InputStream in = new BufferedInputStream(s.getInputStream());
      FrameCodec.Frame f;
      while (running && (f = FrameCodec.read(in)) != null) {
        if (f.type() != FrameCodec.PEER || f.payload().length < 4) {
          return; // not a peer: drop the connection
        }
        ByteBuffer b = ByteBuffer.wrap(f.payload());
        int from = b.getInt();
        byte[] msg = new byte[b.remaining()];
        b.get(msg);
        if (from >= 0 && from < peers.size() && from != me) {
          inbox.accept(from, msg);
        }
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
