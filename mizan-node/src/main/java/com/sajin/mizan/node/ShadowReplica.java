package com.sajin.mizan.node;

import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.OrderBook;
import com.sajin.mizan.engine.RestingOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A replica that only replays (sandbox): every validator reports each committed height with its
 * commands; the first report of the next height is applied to a private {@link ExecutionCore}, the
 * rest are ignored. Because execution is deterministic, this core equals every validator's state at
 * the same height, which {@link Sandbox} checks by comparing state roots. It also derives the
 * market views (trades, candles, session statistics) from the committed trades.
 */
final class ShadowReplica implements AutoCloseable {
  static final int KEEP_TRADES = 400;
  static final int KEEP_BLOCKS = 160;
  static final int KEEP_CANDLES = 1_800;
  static final int KEEP_ROOTS = 4_096;
  static final long ME = Sandbox.YOU;

  private final GenesisConfig genesis;
  private final int validators;
  private final ExecutionCore core;
  private final Map<Integer, String> symbols;
  private final ReentrantLock lock = new ReentrantLock();
  private final ExecutorService exec =
      Executors.newSingleThreadExecutor(
          r -> Thread.ofPlatform().name("shadow").daemon(true).unstarted(r));
  private final Map<Long, Integer> rounds = new ConcurrentHashMap<>();

  // ---- guarded by lock ----
  private long height;
  private final TreeMap<Long, List<SignedCommand>> waiting = new TreeMap<>();
  private final ArrayDeque<SandboxView.Trade> trades = new ArrayDeque<>();
  private final ArrayDeque<SandboxView.Block> blocks = new ArrayDeque<>();
  private final TreeMap<Long, byte[]> roots = new TreeMap<>();
  private final Map<Integer, Session> sessions = new TreeMap<>();
  private long commands;
  private long accepted;
  private long rejected;
  private long tradeCount;
  private final ArrayDeque<long[]> rate = new ArrayDeque<>(); // {millis, commands, trades, blocks}

  /** Per-instrument session statistics and one-second candles. */
  private static final class Session {
    long open = -1;
    long last = -1;
    long high = Long.MIN_VALUE;
    long low = Long.MAX_VALUE;
    long volume;
    long notional;
    long trades;
    final ArrayDeque<long[]> candles = new ArrayDeque<>(); // {start, o, h, l, c, v, n}

    void add(long price, long qty, long millis) {
      if (open < 0) {
        open = price;
      }
      last = price;
      high = Math.max(high, price);
      low = Math.min(low, price);
      volume += qty;
      notional += price * qty;
      trades++;
      long start = millis / 1_000 * 1_000;
      long[] c = candles.peekLast();
      if (c == null || c[0] != start) {
        long o = c == null ? price : c[4];
        candles.addLast(
            new long[] {start, o, Math.max(o, price), Math.min(o, price), price, qty, 1});
        while (candles.size() > KEEP_CANDLES) {
          candles.removeFirst();
        }
      } else {
        c[2] = Math.max(c[2], price);
        c[3] = Math.min(c[3], price);
        c[4] = price;
        c[5] += qty;
        c[6]++;
      }
    }
  }

  ShadowReplica(GenesisConfig g, Map<Integer, String> symbols) {
    this.genesis = g;
    this.validators = g.validators().size();
    this.core = ExecutionCore.genesis(g, SignatureVerifier.ed25519(), false);
    this.symbols = symbols;
    for (int i : core.instrumentIds()) {
      sessions.put(i, new Session());
    }
  }

  /** A validator committed {@code height} (any thread). */
  void offer(long h, List<SignedCommand> cmds) {
    long now = System.currentTimeMillis();
    exec.execute(() -> apply(h, cmds, now));
  }

  /** The round at which {@code height} was decided (from any validator's observer). */
  void decidedRound(long h, int round) {
    rounds.putIfAbsent(h, round);
  }

  private void apply(long h, List<SignedCommand> cmds, long arrival) {
    lock.lock();
    try {
      if (h <= height || waiting.containsKey(h)) {
        return; // another validator already delivered it
      }
      waiting.put(h, cmds);
      while (!waiting.isEmpty() && waiting.firstKey() == height + 1) {
        execute(height + 1, waiting.pollFirstEntry().getValue(), arrival);
      }
    } finally {
      lock.unlock();
    }
  }

  private void execute(long h, List<SignedCommand> cmds, long millis) {
    List<Trade> fills = new ArrayList<>();
    EngineListener l =
        new EngineListener() {
          @Override
          public void onTrade(Trade t) {
            fills.add(t);
          }
        };
    int ok = 0;
    for (SignedCommand c : cmds) {
      ExecResult r = core.execute(c, l);
      if (r.status() == ExecResult.Status.ACCEPTED) {
        ok++;
      }
    }
    height = h;
    commands += cmds.size();
    accepted += ok;
    rejected += cmds.size() - ok;
    tradeCount += fills.size();
    for (Trade t : fills) {
      sessions.get(t.instrument()).add(t.price(), t.qty(), millis);
      trades.addFirst(
          new SandboxView.Trade(
              t.seq(),
              h,
              t.instrument(),
              symbols.getOrDefault(t.instrument(), "#" + t.instrument()),
              t.takerSide(),
              t.price(),
              t.qty(),
              millis,
              t.buyAccount() == ME || t.sellAccount() == ME));
    }
    while (trades.size() > KEEP_TRADES) {
      trades.removeLast();
    }
    byte[] root = core.stateRoot();
    roots.put(h, root);
    while (roots.size() > KEEP_ROOTS) {
      roots.pollFirstEntry();
    }
    int round = rounds.getOrDefault(h, -1);
    rounds.remove(h);
    blocks.addFirst(
        new SandboxView.Block(
            h,
            round,
            round < 0 ? -1 : (int) ((h + round) % validators),
            cmds.size(),
            ok,
            fills.size(),
            millis,
            HexFormat.of().formatHex(root, 0, 8)));
    while (blocks.size() > KEEP_BLOCKS) {
      blocks.removeLast();
    }
    rate.addLast(new long[] {millis, cmds.size(), fills.size(), 1});
    while (!rate.isEmpty() && rate.peekFirst()[0] < millis - 10_000) {
      rate.removeFirst();
    }
  }

  long height() {
    lock.lock();
    try {
      return height;
    } finally {
      lock.unlock();
    }
  }

  /** The shadow's state root at {@code h}, or {@code null} if not (or no longer) known. */
  byte[] rootAt(long h) {
    lock.lock();
    try {
      return roots.get(h);
    } finally {
      lock.unlock();
    }
  }

  /** Everything the dashboard reads from the replica, consistent at one height. */
  @SuppressWarnings("ArrayRecordComponent") // internal hand-off, root copied on creation
  record Views(
      long height,
      byte[] root,
      List<SandboxView.Instrument> instruments,
      List<SandboxView.Trade> trades,
      List<SandboxView.Block> blocks,
      List<SandboxView.OpenOrder> myOpen,
      List<SandboxView.Balance> myBalances,
      long commands,
      long accepted,
      long rejected,
      long tradeCount,
      double commandsPerSec,
      double tradesPerSec,
      double blocksPerSec) {}

  Views views(Map<Integer, Long> fair, int depth) {
    lock.lock();
    try {
      List<SandboxView.Instrument> ins = new ArrayList<>();
      for (int i : core.instrumentIds()) {
        Session s = sessions.get(i);
        List<SandboxView.Level> bids = levels(core.depth(i, Side.BUY, depth));
        List<SandboxView.Level> asks = levels(core.depth(i, Side.SELL, depth));
        List<SandboxView.Candle> cs = new ArrayList<>(s.candles.size());
        for (long[] c : s.candles) {
          cs.add(new SandboxView.Candle(c[0], c[1], c[2], c[3], c[4], c[5], (int) c[6]));
        }
        ins.add(
            new SandboxView.Instrument(
                i,
                symbols.getOrDefault(i, "#" + i),
                s.last,
                s.open,
                s.high == Long.MIN_VALUE ? -1 : s.high,
                s.low == Long.MAX_VALUE ? -1 : s.low,
                s.volume,
                s.notional,
                s.trades,
                bids.isEmpty() ? -1 : bids.get(0).price(),
                asks.isEmpty() ? -1 : asks.get(0).price(),
                fair.getOrDefault(i, -1L),
                bids,
                asks,
                cs));
      }
      List<SandboxView.OpenOrder> open = new ArrayList<>();
      for (int i : core.instrumentIds()) {
        for (Side side : Side.values()) {
          for (RestingOrder o : core.restingOrders(i, side)) {
            if (o.account() == ME) {
              open.add(new SandboxView.OpenOrder(o.id(), i, side, o.price(), o.remaining()));
            }
          }
        }
      }
      List<SandboxView.Balance> bal = new ArrayList<>();
      for (GenesisConfig.Asset a : genesis.assets()) {
        bal.add(
            new SandboxView.Balance(
                a.id(), a.name(), core.free(ME, a.id()), core.held(ME, a.id())));
      }
      long[] sum = new long[4];
      long now = System.currentTimeMillis();
      for (long[] r : rate) {
        if (r[0] >= now - 5_000) {
          sum[1] += r[1];
          sum[2] += r[2];
          sum[3] += r[3];
        }
      }
      byte[] root = roots.isEmpty() ? core.stateRoot() : roots.lastEntry().getValue();
      return new Views(
          height,
          Arrays.copyOf(root, root.length),
          ins,
          List.copyOf(trades),
          List.copyOf(blocks),
          open,
          bal,
          commands,
          accepted,
          rejected,
          tradeCount,
          sum[1] / 5.0,
          sum[2] / 5.0,
          sum[3] / 5.0);
    } finally {
      lock.unlock();
    }
  }

  private static List<SandboxView.Level> levels(List<OrderBook.Level> ls) {
    List<SandboxView.Level> out = new ArrayList<>(ls.size());
    long cum = 0;
    for (OrderBook.Level l : ls) {
      cum += l.qty();
      out.add(new SandboxView.Level(l.price(), l.qty(), cum));
    }
    return out;
  }

  @Override
  public void close() {
    exec.shutdownNow();
  }
}
