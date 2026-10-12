package com.sajin.mizan.ui;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.net.md.DepthBook;
import com.sajin.mizan.net.md.MdCodec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Toolkit-free view model of the dashboard: ladder rows, recent trades and a status line. All
 * methods are called on one thread (the JavaFX application thread in the app, the test thread in
 * tests), so it is plain mutable state.
 */
public final class LadderModel {
  public static final int DEPTH = 15;
  public static final int TRADES = 20;

  /** How long the REVOKE banner stays lit after a revoke. */
  public static final long REVOKE_FLASH_MS = 3_000;

  /** One point of the cumulative depth chart. */
  public record DepthPoint(long price, long cumulativeQty) {}

  /** One ladder row. {@code bar} is 0..1 relative to the largest quantity shown. */
  public record Row(long price, long qty, double bar) {
    public String text() {
      return String.format("%8d  %10d", price, qty);
    }
  }

  private final int instrument;
  private List<Row> bids = List.of();
  private List<Row> asks = List.of();
  private final ArrayDeque<String> trades = new ArrayDeque<>();
  private long seq;
  private long gaps;
  private long snapshots;
  private long revokes;
  private long lastRevokeFrom = -1;
  private long lastRevokeAtMillis = Long.MIN_VALUE;
  private List<DepthPoint> bidCurve = List.of();
  private List<DepthPoint> askCurve = List.of();

  public LadderModel(int instrument) {
    this.instrument = instrument;
  }

  public int instrument() {
    return instrument;
  }

  /** Replaces the ladder from the subscriber's current view. Asks are shown best-at-bottom. */
  public void setBook(List<DepthBook.Level> bidLevels, List<DepthBook.Level> askLevels) {
    long max = 1;
    for (DepthBook.Level l : bidLevels) {
      max = Math.max(max, l.qty());
    }
    for (DepthBook.Level l : askLevels) {
      max = Math.max(max, l.qty());
    }
    List<Row> b = new ArrayList<>();
    for (DepthBook.Level l : bidLevels.subList(0, Math.min(DEPTH, bidLevels.size()))) {
      b.add(new Row(l.price(), l.qty(), (double) l.qty() / max));
    }
    List<Row> a = new ArrayList<>();
    for (DepthBook.Level l : askLevels.subList(0, Math.min(DEPTH, askLevels.size()))) {
      a.add(0, new Row(l.price(), l.qty(), (double) l.qty() / max));
    }
    bids = List.copyOf(b);
    asks = List.copyOf(a);
    bidCurve = cumulative(bidLevels);
    askCurve = cumulative(askLevels);
  }

  /** Cumulative quantity from the best level outwards (all levels the view has, not just DEPTH). */
  private static List<DepthPoint> cumulative(List<DepthBook.Level> levels) {
    List<DepthPoint> out = new ArrayList<>(levels.size());
    long sum = 0;
    for (DepthBook.Level l : levels) {
      sum += l.qty();
      out.add(new DepthPoint(l.price(), sum));
    }
    return List.copyOf(out);
  }

  /** Bid depth curve: best (highest) price first, cumulative quantity rising. */
  public List<DepthPoint> bidCurve() {
    return bidCurve;
  }

  /** Ask depth curve: best (lowest) price first, cumulative quantity rising. */
  public List<DepthPoint> askCurve() {
    return askCurve;
  }

  public void onTrade(MdCodec.TradePrint t) {
    if (t.instrument() != instrument) {
      return;
    }
    trades.addFirst(
        String.format(
            "#%d %s %d @ %d",
            t.tradeSeq(), t.takerSide() == Side.BUY ? "BUY " : "SELL", t.qty(), t.price()));
    while (trades.size() > TRADES) {
      trades.removeLast();
    }
  }

  public void onRevoke(long fromTradeSeq) {
    onRevoke(fromTradeSeq, System.currentTimeMillis());
  }

  /** As above at {@code nowMillis} (tests pass a fixed clock). */
  public void onRevoke(long fromTradeSeq, long nowMillis) {
    lastRevokeAtMillis = nowMillis;
    revokes++;
    lastRevokeFrom = fromTradeSeq;
    trades.removeIf(s -> tradeSeq(s) >= fromTradeSeq);
  }

  private static long tradeSeq(String line) {
    return Long.parseLong(line.substring(1, line.indexOf(' ')));
  }

  public void onStatus(long lastSeq, long gapCount, long snapshotCount) {
    seq = lastSeq;
    gaps = gapCount;
    snapshots = snapshotCount;
  }

  public List<Row> bids() {
    return bids;
  }

  public List<Row> asks() {
    return asks;
  }

  public List<String> trades() {
    return List.copyOf(trades);
  }

  /** Spread between best ask and best bid, or {@code -1} if either side is empty. */
  public long spread() {
    return bids.isEmpty() || asks.isEmpty()
        ? -1
        : asks.get(asks.size() - 1).price() - bids.get(0).price();
  }

  /** Whether the REVOKE banner is lit at {@code nowMillis}. */
  public boolean revokeFlash(long nowMillis) {
    return lastRevokeAtMillis != Long.MIN_VALUE && nowMillis - lastRevokeAtMillis < REVOKE_FLASH_MS;
  }

  public long revokeCount() {
    return revokes;
  }

  public long lastRevokeFrom() {
    return lastRevokeFrom;
  }

  public String status() {
    String s =
        "instrument "
            + instrument
            + " | seq "
            + seq
            + " | gaps "
            + gaps
            + " | snapshots "
            + snapshots;
    if (revokes > 0) {
      s += " | REVOKED from trade #" + lastRevokeFrom + " (" + revokes + ")";
    }
    return s;
  }
}
