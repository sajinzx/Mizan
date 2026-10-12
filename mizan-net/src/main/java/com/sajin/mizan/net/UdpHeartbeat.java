package com.sajin.mizan.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Validator heartbeats over UDP (M8-T5): every {@code periodMs} each validator sends {@code
 * [1][from:4][height:8][headHash:32]} to every peer, on the same port number as its TCP peer
 * listener. Heartbeats are advisory and unauthenticated: they only make a lagging validator ask a
 * peer for blocks, and block sync verifies every certificate. Loss is harmless, since the next
 * heartbeat repeats the information.
 */
public final class UdpHeartbeat implements AutoCloseable {
  /** Receives {@code (peer, height)} on the receiver thread. */
  @FunctionalInterface
  public interface Listener {
    void onPeerHeight(int peer, long height);
  }

  private static final int PACKET = 1 + 4 + 8 + 32;

  private final int me;
  private final List<InetSocketAddress> peers;
  private final LongSupplier height;
  private final Supplier<byte[]> head;
  private final Listener listener;
  private final long periodMs;
  private final double dropForTest;
  private final SplittableRandom rnd = new SplittableRandom(1);
  private DatagramSocket socket;
  private volatile boolean running = true;
  private Thread sender;
  private Thread receiver;

  public UdpHeartbeat(
      int me,
      List<InetSocketAddress> peers,
      LongSupplier height,
      Supplier<byte[]> head,
      Listener listener,
      long periodMs,
      double dropForTest) {
    this.me = me;
    this.peers = List.copyOf(peers);
    this.height = height;
    this.head = head;
    this.listener = listener;
    this.periodMs = periodMs;
    this.dropForTest = dropForTest;
  }

  public void start() throws SocketException {
    socket = new DatagramSocket(peers.get(me));
    sender = Thread.ofPlatform().name("gossip-udp-send-" + me).daemon(true).start(this::sendLoop);
    receiver =
        Thread.ofPlatform().name("gossip-udp-recv-" + me).daemon(true).start(this::receiveLoop);
  }

  private void sendLoop() {
    while (running) {
      byte[] h = head.get();
      byte[] p =
          ByteBuffer.allocate(PACKET)
              .put((byte) 1)
              .putInt(me)
              .putLong(height.getAsLong())
              .put(h.length == 32 ? h : new byte[32])
              .array();
      for (int i = 0; i < peers.size(); i++) {
        if (i != me) {
          try {
            socket.send(new DatagramPacket(p, p.length, peers.get(i)));
          } catch (IOException ignored) {
            // best effort
          }
        }
      }
      try {
        Thread.sleep(periodMs);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private void receiveLoop() {
    byte[] buf = new byte[256];
    while (running) {
      try {
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        socket.receive(p);
        if (p.getLength() != PACKET || buf[0] != 1) {
          continue;
        }
        synchronized (rnd) {
          if (rnd.nextDouble() < dropForTest) {
            continue;
          }
        }
        ByteBuffer b = ByteBuffer.wrap(buf, 1, PACKET - 1);
        int from = b.getInt();
        long h = b.getLong();
        if (from >= 0 && from < peers.size() && from != me && h >= 0) {
          listener.onPeerHeight(from, h);
        }
      } catch (IOException e) {
        if (!running) {
          return;
        }
      }
    }
  }

  @Override
  public void close() {
    running = false;
    if (socket != null) {
      socket.close();
    }
    if (sender != null) {
      sender.interrupt();
    }
    if (receiver != null) {
      try {
        receiver.join(1_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
