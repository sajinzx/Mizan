package com.sajin.mizan.net.md;

import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Side;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * UDP market-data subscriber (M5-T3). It re-subscribes every second and keeps one book per
 * instrument, replaced by each newer {@code DEPTH} packet (packets older than the instrument's last
 * applied sequence are ignored, so reordering and duplicates are harmless).
 *
 * <p>Gap handling: a sequence jump, or a heartbeat ahead of everything received, triggers one TCP
 * snapshot request. When the snapshot (at sequence {@code S}) arrives, it replaces every instrument
 * whose last applied sequence is below {@code S}; later packets then apply on top. Lost {@code
 * TRADE} and {@code REVOKE} prints are not recovered (they are informational; the client's own
 * fills come over TCP).
 */
public final class MarketDataClient implements AutoCloseable {

  /** Fetches a TCP snapshot payload; called on a virtual thread. */
  @FunctionalInterface
  public interface SnapshotSource {
    byte[] fetch() throws IOException;
  }

  /** Callbacks on the client's receive thread; must be quick. */
  public interface Listener {
    default void onBook(int instrument) {}

    default void onTrade(MdCodec.TradePrint t) {}

    default void onRevoke(long fromTradeSeq) {}

    default void onGap(long expected, long got) {}

    default void onSnapshot(long seq) {}
  }

  private record Book(List<DepthBook.Level> bids, List<DepthBook.Level> asks) {}

  private final DatagramSocket socket;
  private final InetSocketAddress publisher;
  private final SnapshotSource snapshots;
  private final Listener listener;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Integer, Book> books = new HashMap<>();
  private final Map<Integer, Long> appliedSeq = new HashMap<>();
  private final AtomicBoolean snapshotInFlight = new AtomicBoolean();
  private final AtomicLong gaps = new AtomicLong();
  private final AtomicLong snapshotsApplied = new AtomicLong();
  private final Thread thread;
  private long lastSeq;
  private volatile boolean running = true;

  public MarketDataClient(InetSocketAddress publisher, SnapshotSource snapshots, Listener listener)
      throws IOException {
    this.socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    socket.setSoTimeout(200);
    this.publisher = publisher;
    this.snapshots = snapshots;
    this.listener = listener == null ? new Listener() {} : listener;
    this.thread = Thread.ofVirtual().name("md-client").start(this::loop);
  }

  public int localPort() {
    return socket.getLocalPort();
  }

  public long gapCount() {
    return gaps.get();
  }

  public long snapshotCount() {
    return snapshotsApplied.get();
  }

  public long lastSeq() {
    lock.lock();
    try {
      return lastSeq;
    } finally {
      lock.unlock();
    }
  }

  /** Up to {@code n} best levels of the current view; empty if unknown. */
  public List<DepthBook.Level> levels(int instrument, Side side, int n) {
    lock.lock();
    try {
      Book b = books.get(instrument);
      if (b == null) {
        return List.of();
      }
      List<DepthBook.Level> src = side == Side.BUY ? b.bids() : b.asks();
      return List.copyOf(src.subList(0, Math.min(n, src.size())));
    } finally {
      lock.unlock();
    }
  }

  private void loop() {
    byte[] buf = new byte[MdCodec.MAX_PACKET + 64];
    long nextSubscribe = 0;
    while (running) {
      long now = System.nanoTime();
      if (now >= nextSubscribe) {
        try {
          byte[] sub = MdCodec.subscribe();
          socket.send(new DatagramPacket(sub, sub.length, publisher));
        } catch (IOException ignored) {
          // retried next second
        }
        nextSubscribe = now + TimeUnit.SECONDS.toNanos(1);
      }
      try {
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        socket.receive(p);
        handle(MdCodec.decode(buf, p.getLength()));
      } catch (SocketTimeoutException e) {
        // poll again
      } catch (ProtocolException e) {
        // ignore malformed datagrams
      } catch (IOException e) {
        if (!running) {
          return;
        }
      }
    }
  }

  private void handle(MdCodec.Packet p) {
    boolean gap;
    long expected;
    lock.lock();
    try {
      expected = lastSeq + 1;
      gap = p instanceof MdCodec.Heartbeat ? p.seq() > lastSeq : p.seq() > lastSeq + 1;
      if (p instanceof MdCodec.Depth d && d.seq() > appliedSeq.getOrDefault(d.instrument(), 0L)) {
        books.put(d.instrument(), new Book(d.bids(), d.asks()));
        appliedSeq.put(d.instrument(), d.seq());
      }
      if (!(p instanceof MdCodec.Heartbeat)) {
        lastSeq = Math.max(lastSeq, p.seq());
      }
    } finally {
      lock.unlock();
    }
    switch (p) {
      case MdCodec.Depth d -> listener.onBook(d.instrument());
      case MdCodec.TradePrint t -> listener.onTrade(t);
      case MdCodec.Revoke r -> listener.onRevoke(r.fromTradeSeq());
      case MdCodec.Heartbeat h -> {}
    }
    if (gap) {
      gaps.incrementAndGet();
      listener.onGap(expected, p.seq());
      requestSnapshot();
    }
  }

  private void requestSnapshot() {
    if (!snapshotInFlight.compareAndSet(false, true)) {
      return;
    }
    Thread.ofVirtual()
        .name("md-snapshot")
        .start(
            () -> {
              try {
                applySnapshot(MdCodec.decodeSnapshot(snapshots.fetch()));
              } catch (IOException e) {
                // the next gap or heartbeat retries
              } finally {
                snapshotInFlight.set(false);
              }
            });
  }

  private void applySnapshot(MdCodec.Snapshot s) {
    List<Integer> changed = new ArrayList<>();
    lock.lock();
    try {
      for (MdCodec.Book b : s.books()) {
        if (appliedSeq.getOrDefault(b.instrument(), 0L) < s.seq()) {
          books.put(b.instrument(), new Book(b.bids(), b.asks()));
          appliedSeq.put(b.instrument(), s.seq());
          changed.add(b.instrument());
        }
      }
      lastSeq = Math.max(lastSeq, s.seq());
    } finally {
      lock.unlock();
    }
    snapshotsApplied.incrementAndGet();
    listener.onSnapshot(s.seq());
    changed.forEach(listener::onBook);
  }

  @Override
  public void close() {
    running = false;
    socket.close();
    try {
      thread.join(2_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
