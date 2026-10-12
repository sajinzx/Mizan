package com.sajin.mizan.node;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDP man-in-the-middle for tests. Subscribers talk to the proxy; the proxy subscribes upstream
 * from its own socket and forwards downstream packets with seeded loss, duplication and random
 * delay (which reorders). Upstream traffic (subscriptions) is forwarded unchanged.
 */
final class LossyUdpProxy implements AutoCloseable {
  private final DatagramSocket downstream; // subscriber <-> proxy
  private final DatagramSocket upstream; // proxy <-> publisher
  private final SocketAddress publisher;
  private final double loss;
  private final double duplicate;
  private final int maxDelayMs;
  private final SplittableRandom rnd;
  private final ScheduledExecutorService delayer = Executors.newSingleThreadScheduledExecutor();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong forwarded = new AtomicLong();
  private volatile SocketAddress subscriber;
  private volatile boolean running = true;

  LossyUdpProxy(int publisherPort, double loss, double duplicate, int maxDelayMs, long seed)
      throws IOException {
    InetAddress lo = InetAddress.getLoopbackAddress();
    this.downstream = new DatagramSocket(new InetSocketAddress(lo, 0));
    this.upstream = new DatagramSocket(new InetSocketAddress(lo, 0));
    this.publisher = new InetSocketAddress(lo, publisherPort);
    this.loss = loss;
    this.duplicate = duplicate;
    this.maxDelayMs = maxDelayMs;
    this.rnd = new SplittableRandom(seed);
    Thread.ofVirtual().start(this::fromSubscriber);
    Thread.ofVirtual().start(this::fromPublisher);
  }

  int port() {
    return downstream.getLocalPort();
  }

  long dropped() {
    return dropped.get();
  }

  long forwarded() {
    return forwarded.get();
  }

  private void fromSubscriber() {
    byte[] buf = new byte[2048];
    while (running) {
      try {
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        downstream.receive(p);
        subscriber = p.getSocketAddress();
        upstream.send(new DatagramPacket(buf, p.getLength(), publisher));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void fromPublisher() {
    byte[] buf = new byte[2048];
    while (running) {
      try {
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        upstream.receive(p);
        byte[] data = Arrays.copyOf(buf, p.getLength());
        SocketAddress to = subscriber;
        if (to == null) {
          continue;
        }
        double roll;
        int copies;
        long delay;
        synchronized (rnd) {
          roll = rnd.nextDouble();
          copies = rnd.nextDouble() < duplicate ? 2 : 1;
          delay = rnd.nextInt(maxDelayMs + 1);
        }
        if (roll < loss) {
          dropped.incrementAndGet();
          continue;
        }
        for (int i = 0; i < copies; i++) {
          var unused = delayer.schedule(() -> send(data, to), delay + i, TimeUnit.MILLISECONDS);
        }
      } catch (IOException e) {
        return;
      }
    }
  }

  private void send(byte[] data, SocketAddress to) {
    try {
      downstream.send(new DatagramPacket(data, data.length, to));
      forwarded.incrementAndGet();
    } catch (IOException ignored) {
      // closing
    }
  }

  @Override
  public void close() {
    running = false;
    downstream.close();
    upstream.close();
    delayer.shutdownNow();
  }
}
