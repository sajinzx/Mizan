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
