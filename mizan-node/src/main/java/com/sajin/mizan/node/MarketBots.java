package com.sajin.mizan.node;

import com.sajin.mizan.core.Cancel;
import com.sajin.mizan.core.OrderCommand;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.crypto.CryptoException;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.sim.MizanClient;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * Sandbox liquidity (not part of the exchange): market makers that keep layered quotes around a
 * random-walking fair price and cancel stale ones, and takers that cross the spread with IOC and
 * market orders. Every bot is a real client: its own account and Ed25519 key, a TCP connection to a
 * live validator's gateway (failing over when that validator stops), and its own nonces.
 */
final class MarketBots implements AutoCloseable {
  static final int MAX_MAKERS = 16;
  static final int MAX_TAKERS = 16;
  static final long FIRST_MAKER = 2_000;
  static final long FIRST_TAKER = 3_000;
  static final int MAX_IN_FLIGHT = 48;

  private final int chainId;
  private final Supplier<List<Integer>> liveGateways;
  private final Map<Integer, AtomicLong> fair = new TreeMap<>();
  private final Map<Integer, Long> anchor = new TreeMap<>();
  private final List<Bot> makers = new ArrayList<>();
  private final List<Bot> takers = new ArrayList<>();
  private final SplittableRandom rnd = new SplittableRandom(42);
  private final Recorder ackLatency = new Recorder(3);
  private final Recorder finalLatency = new Recorder(3);
  private final AtomicLong sent = new AtomicLong();
  private final AtomicLong busy = new AtomicLong();
  private final Thread driver;

  private volatile int activeMakers = 6;
  private volatile int activeTakers = 4;
  private volatile int ratePerSec = 120;
  private volatile int volatilityBps = 8;
  private volatile boolean running = true;
  private volatile boolean closed;
  private Histogram ackWindow;
  private Histogram finalWindow;
  private long lastWindowMillis;
  private long[] lastAck = {0, 0};
  private long[] lastFinal = {0, 0};

  private static final class Bot {
    final long account;
    final boolean maker;
    final AtomicLong nonce = new AtomicLong(1); // 1 = Register
    final AtomicInteger inFlight = new AtomicInteger();
    final ArrayDeque<Long> resting = new ArrayDeque<>();
    MizanClient client;
    int via = -1;

    Bot(long account, boolean maker) {
      this.account = account;
      this.maker = maker;
    }
  }

  MarketBots(int chainId, Map<Integer, Long> startPrices, Supplier<List<Integer>> liveGateways) {
    this.chainId = chainId;
    this.liveGateways = liveGateways;
    startPrices.forEach(
        (i, p) -> {
          fair.put(i, new AtomicLong(p));
          anchor.put(i, p);
        });
    for (int k = 0; k < MAX_MAKERS; k++) {
      makers.add(new Bot(FIRST_MAKER + k, true));
    }
    for (int k = 0; k < MAX_TAKERS; k++) {
      takers.add(new Bot(FIRST_TAKER + k, false));
    }
    driver = Thread.ofPlatform().name("bots").daemon(true).unstarted(this::loop);
  }

  /** All bot accounts, for funding at sandbox start. */
  List<Long> accounts() {
    List<Long> out = new ArrayList<>();
    makers.forEach(b -> out.add(b.account));
    takers.forEach(b -> out.add(b.account));
    return out;
  }

  void start() {
    driver.start();
  }

  void configure(int makers, int takers, int rate, int volBps, boolean run) {
    this.activeMakers = Math.clamp(makers, 0, MAX_MAKERS);
    this.activeTakers = Math.clamp(takers, 0, MAX_TAKERS);
    this.ratePerSec = Math.clamp(rate, 0, 5_000);
    this.volatilityBps = Math.clamp(volBps, 0, 200);
    this.running = run;
  }

  SandboxView.Bots settings() {
    return new SandboxView.Bots(activeMakers, activeTakers, ratePerSec, volatilityBps, running);
  }

  Map<Integer, Long> fairPrices() {
    Map<Integer, Long> out = new TreeMap<>();
    fair.forEach((i, v) -> out.put(i, v.get()));
    return out;
  }

  long sent() {
    return sent.get();
  }

  long busy() {
    return busy.get();
  }

  /** p50/p99 in µs over the last few seconds: {ackP50, ackP99, finalP50, finalP99}. */
  synchronized long[] latencies() {
    long now = System.currentTimeMillis();
    if (now - lastWindowMillis >= 2_000) {
      ackWindow = ackLatency.getIntervalHistogram(ackWindow);
      finalWindow = finalLatency.getIntervalHistogram(finalWindow);
      if (ackWindow.getTotalCount() > 0) {
        lastAck =
            new long[] {ackWindow.getValueAtPercentile(50), ackWindow.getValueAtPercentile(99)};
      }
      if (finalWindow.getTotalCount() > 0) {
        lastFinal =
            new long[] {finalWindow.getValueAtPercentile(50), finalWindow.getValueAtPercentile(99)};
      }
      lastWindowMillis = now;
    }
    return new long[] {lastAck[0], lastAck[1], lastFinal[0], lastFinal[1]};
  }

  private void loop() {
    double owed = 0;
    long last = System.nanoTime();
    long lastWalk = 0;
    int next = 0;
    while (!closed) {
      long now = System.nanoTime();
      double dt = (now - last) / 1e9;
      last = now;
      if (now - lastWalk > 200_000_000L) {
        walk();
        lastWalk = now;
      }
      if (running) {
        owed = Math.min(owed + ratePerSec * dt, 200);
        int m = activeMakers;
        int t = activeTakers;
        while (owed >= 1 && m + t > 0) {
          owed -= 1;
          int k = next++ % (m + t);
          // makers carry about two thirds of the flow, as in a real book
          Bot b =
              rnd.nextInt(3) < 2 && m > 0
                  ? makers.get(k % m)
                  : t > 0 ? takers.get(k % t) : makers.get(k % m);
          act(b);
        }
      } else {
        owed = 0;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  /** Mean-reverting random walk of each fair price, in ticks. */
  private void walk() {
    fair.forEach(
        (i, f) -> {
          long a = anchor.get(i);
          long p = f.get();
          double shock = rnd.nextGaussian() * volatilityBps / 10_000.0 * p;
          double pull = (a - p) * 0.01;
          f.set(Math.max(10, Math.round(p + shock + pull)));
        });
  }

  private void act(Bot b) {
    if (b.inFlight.get() >= MAX_IN_FLIGHT) {
      return;
    }
    List<Integer> instruments = new ArrayList<>(fair.keySet());
    int instrument = instruments.get(rnd.nextInt(instruments.size()));
    long f = fair.get(instrument).get();
    long tick = Math.max(1, f / 2_000); // ~5 bps per level
    OrderCommand body;
    if (b.maker) {
      Long stale;
      synchronized (b.resting) {
        stale =
            b.resting.size() > 10 || (b.resting.size() > 3 && rnd.nextInt(4) == 0)
                ? b.resting.pollFirst()
                : null;
      }
      if (stale != null) {
        body = new Cancel(stale >>> 8, (int) (stale & 0xFF));
      } else {
        Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
        long level = 1L + rnd.nextInt(6);
        long price = side == Side.BUY ? f - level * tick : f + level * tick;
        long qty = 1L + rnd.nextInt(rnd.nextInt(10) == 0 ? 120 : 25);
        body =
            new Place(
                b.nonce.get() + 1,
                instrument,
                side,
                OrderType.LIMIT,
                rnd.nextInt(8) == 0 ? TimeInForce.POST_ONLY : TimeInForce.GTC,
                price,
                qty);
      }
    } else {
      Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
      long qty = 1L + rnd.nextInt(rnd.nextInt(8) == 0 ? 60 : 12);
      long collar = side == Side.BUY ? f + 8 * tick : f - 8 * tick;
      boolean market = rnd.nextInt(3) == 0;
      body =
          new Place(
              b.nonce.get() + 1,
              instrument,
              side,
              market ? OrderType.MARKET : OrderType.LIMIT,
              market ? TimeInForce.IOC : rnd.nextInt(5) == 0 ? TimeInForce.FOK : TimeInForce.IOC,
              collar,
              qty);
    }
    send(b, body);
  }

  private void send(Bot b, OrderCommand body) {
    MizanClient c = client(b);
    if (c == null) {
      return;
    }
    long nonce = b.nonce.incrementAndGet();
    SignedCommand cmd;
    try {
      cmd = Tools.sign(DevGenesis.accountKey(b.account), chainId, b.account, nonce, body);
    } catch (CryptoException e) {
      return;
    }
    long t0 = System.nanoTime();
    b.inFlight.incrementAndGet();
    sent.incrementAndGet();
    CompletableFuture<Messages.ServerMessage> ack;
    try {
      ack = c.send(cmd);
    } catch (IOException e) {
      b.inFlight.decrementAndGet();
      drop(b);
      return;
    }
    boolean timeFinal = nonce % 8 == 0;
    var unused =
        ack.whenComplete(
            (m, err) -> {
              b.inFlight.decrementAndGet();
              if (err != null) {
                drop(b);
                return;
              }
              ackLatency.recordValue(Math.max(1, (System.nanoTime() - t0) / 1_000));
              if (m instanceof Messages.Busy) {
                busy.incrementAndGet();
              }
              long orderId =
                  m instanceof Messages.Accepted a
                      ? a.orderId()
                      : m instanceof Messages.Final f && f.status() == Messages.Final.ACCEPTED
                          ? f.orderId()
                          : 0;
              if (b.maker && orderId > 0 && body instanceof Place p) {
                synchronized (b.resting) {
                  b.resting.addLast(orderId << 8 | p.instrument());
                }
              }
              if (timeFinal) {
                if (m instanceof Messages.Final) {
                  finalLatency.recordValue(Math.max(1, (System.nanoTime() - t0) / 1_000));
                } else if (m instanceof Messages.Accepted || m instanceof Messages.Rejected) {
                  var unusedFinal =
                      c.finality(b.account, nonce)
                          .whenComplete(
                              (f, e2) -> {
                                if (e2 == null) {
                                  finalLatency.recordValue(
                                      Math.max(1, (System.nanoTime() - t0) / 1_000));
                                }
                              });
                }
              }
            });
  }

  private synchronized MizanClient client(Bot b) {
    if (b.client != null) {
      return b.client;
    }
    List<Integer> live = liveGateways.get();
    if (live.isEmpty()) {
      return null;
    }
    int port = live.get((int) (b.account % live.size()));
    try {
      b.client = new MizanClient("127.0.0.1", port);
      b.via = port;
    } catch (IOException e) {
      b.client = null;
    }
    return b.client;
  }

  private synchronized void drop(Bot b) {
    if (b.client != null) {
      try {
        b.client.close();
      } catch (IOException ignored) {
        // already broken
      }
      b.client = null;
    }
  }

  /** Drops every connection to {@code port} (its validator stopped); bots reconnect elsewhere. */
  synchronized void forget(int port) {
    for (Bot b : makers) {
      if (b.via == port) {
        drop(b);
      }
    }
    for (Bot b : takers) {
      if (b.via == port) {
        drop(b);
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    driver.interrupt();
    for (Bot b : makers) {
      drop(b);
    }
    for (Bot b : takers) {
      drop(b);
    }
  }
}
