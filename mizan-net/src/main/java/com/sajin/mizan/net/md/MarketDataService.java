package com.sajin.mizan.net.md;

import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

/**
 * Market data (§6.6, M5-T1..T3).
 *
 * <ul>
 *   <li><b>Engine side</b> ({@link EngineListener}, sequencer thread, sole producer): book deltas,
 *       trades and revokes go into a bounded SPSC queue. It never blocks; if the queue is full the
 *       event is counted in {@link #overflowCount()}.
 *   <li><b>{@code marketdata} thread</b>: owns a {@link DepthBook} built only from deltas, never
 *       from the engine's book. After each batch it publishes one {@code DEPTH} packet (full top-35
 *       state) per changed instrument, {@code TRADE}/{@code REVOKE} packets as they come, and a
 *       {@code HEARTBEAT} every {@code heartbeatMs}. It also refreshes the TCP snapshot.
 *   <li><b>Subscriptions</b>: subscribers send {@code SUBSCRIBE} datagrams; silent subscribers
 *       expire after 10 s. Unicast fan-out (ADR 0012).
 * </ul>
 */
public final class MarketDataService implements EngineListener, AutoCloseable {
  private static final long SUBSCRIPTION_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);

  /** Default cap on live subscriptions: a flood of SUBSCRIBE datagrams cannot grow the map. */
  public static final int DEFAULT_MAX_SUBSCRIBERS = 1_024;

  private final OneToOneConcurrentArrayQueue<MarketEvent> queue;
  private final AtomicLong overflow = new AtomicLong();
  private final DatagramSocket socket;
  private final long heartbeatNanos;
  private final int maxSubscribers;
  private final Map<SocketAddress, Long> subscribers = new ConcurrentHashMap<>();
  private final AtomicReference<byte[]> snapshot;
  private final AtomicLong published = new AtomicLong();
  private final Thread publisher;
  private final Thread receiver;
  private volatile boolean running = true;

  // ---- owned by the marketdata thread ----
  private final DepthBook book = new DepthBook();
  private final TreeSet<Integer> dirty = new TreeSet<>();
  private long seq;
  private long lastHeartbeat;

  /**
   * @param udpPort UDP port to bind on localhost ({@code 0} = ephemeral)
   * @param instruments instruments to include in snapshots even before their first update
   */
  public MarketDataService(
      int queueCapacity, int udpPort, long heartbeatMs, List<Integer> instruments)
      throws SocketException {
    this(queueCapacity, udpPort, heartbeatMs, instruments, DEFAULT_MAX_SUBSCRIBERS);
  }

  /**
   * As above, with at most {@code maxSubscribers} live subscriptions (new ones beyond are ignored).
   */
  public MarketDataService(
      int queueCapacity,
      int udpPort,
      long heartbeatMs,
      List<Integer> instruments,
      int maxSubscribers)
      throws SocketException {
    this.maxSubscribers = maxSubscribers;
    this.queue = new OneToOneConcurrentArrayQueue<>(queueCapacity);
    this.socket =
        new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), udpPort));
    this.heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatMs);
    instruments.forEach(i -> book.replace(i, List.of(), List.of()));
    this.snapshot = new AtomicReference<>(buildSnapshot());
    this.publisher = Thread.ofPlatform().name("marketdata").daemon(true).start(this::publishLoop);
    this.receiver = Thread.ofVirtual().name("marketdata-subscriptions").start(this::receiveLoop);
  }

  public int udpPort() {
    return socket.getLocalPort();
  }

  /** Events dropped because the queue was full (the depth view is then stale until restart). */
  public long overflowCount() {
    return overflow.get();
  }

  /** Packets published so far (the latest sequence number). */
  public long publishedSeq() {
    return published.get();
  }

  public int subscriberCount() {
    return subscribers.size();
  }

  /** Latest TCP {@code SNAPSHOT} payload; safe from any thread. */
  public byte[] snapshot() {
    return snapshot.get();
  }

  // ------------------------------------------------------------------ engine thread

  @Override
  public void onBookDelta(int instrument, Side side, long price, long aggQty) {
    offer(new MarketEvent.Delta(instrument, side, price, aggQty));
  }

  @Override
  public void onTrade(Trade t) {
    offer(new MarketEvent.Traded(t));
  }

  @Override
  public void onRevoke(long fromTradeSeq) {
    offer(new MarketEvent.Revoked(fromTradeSeq));
  }

  private void offer(MarketEvent e) {
    if (!queue.offer(e)) {
      overflow.incrementAndGet();
    }
  }

  // ------------------------------------------------------------------ marketdata thread

  private void publishLoop() {
    IdleStrategy idle = new BackoffIdleStrategy(10, 10, 1_000, 1_000_000);
    lastHeartbeat = System.nanoTime();
    while (running) {
      int work = 0;
      MarketEvent e;
      while (work < 4_096 && (e = queue.poll()) != null) {
        apply(e);
        work++;
      }
      if (!dirty.isEmpty()) {
        for (int instrument : dirty) {
          send(
              MdCodec.depth(
                  ++seq,
                  instrument,
                  book.levels(instrument, Side.BUY, MdCodec.MAX_LEVELS),
                  book.levels(instrument, Side.SELL, MdCodec.MAX_LEVELS)));
        }
        dirty.clear();
        published.set(seq);
        snapshot.set(buildSnapshot());
      }
      long now = System.nanoTime();
      if (now - lastHeartbeat >= heartbeatNanos) {
        send(MdCodec.heartbeat(seq));
        lastHeartbeat = now;
        subscribers.values().removeIf(t -> now - t > SUBSCRIPTION_TTL_NANOS);
        work++;
      }
      idle.idle(work);
    }
  }

  private void apply(MarketEvent e) {
    switch (e) {
      case MarketEvent.Delta d -> {
        book.apply(d.instrument(), d.side(), d.price(), d.aggQty());
        dirty.add(d.instrument());
      }
      case MarketEvent.Traded t -> {
        Trade x = t.trade();
        send(MdCodec.trade(++seq, x.instrument(), x.seq(), x.takerSide(), x.price(), x.qty()));
        published.set(seq);
      }
      case MarketEvent.Revoked r -> {
        send(MdCodec.revoke(++seq, r.fromTradeSeq()));
        published.set(seq);
      }
    }
  }

  private byte[] buildSnapshot() {
    List<MdCodec.Book> books = new ArrayList<>();
    for (int i : book.instruments()) {
      books.add(new MdCodec.Book(i, book.levels(i, Side.BUY, 0), book.levels(i, Side.SELL, 0)));
    }
    return MdCodec.snapshot(seq, books);
  }

  private void send(byte[] packet) {
    for (SocketAddress a : subscribers.keySet()) {
      try {
        socket.send(new DatagramPacket(packet, packet.length, a));
      } catch (IOException ignored) {
        // UDP is best effort; subscribers recover through heartbeats and snapshots
      }
    }
  }

  // ------------------------------------------------------------------ subscriptions

  private void receiveLoop() {
    byte[] buf = new byte[64];
    while (running) {
      try {
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        socket.receive(p);
        if (p.getLength() == 1 && buf[0] == MdCodec.SUBSCRIBE) {
          SocketAddress from = p.getSocketAddress();
          if (subscribers.containsKey(from) || subscribers.size() < maxSubscribers) {
            subscribers.put(from, System.nanoTime()); // refresh, or a new one under the cap
          }
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
    socket.close();
    try {
      publisher.join(2_000);
      receiver.join(2_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
