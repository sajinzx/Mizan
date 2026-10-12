package com.sajin.mizan.net.md;

import com.sajin.mizan.core.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Aggregated depth (price → total quantity) per instrument and side, rebuilt purely from {@code
 * onBookDelta} events (§6.6). It never reads the engine's {@code OrderBook}. Owned by a single
 * thread.
 */
public final class DepthBook {
  /** One price level. */
  public record Level(long price, long qty) {}

  private final TreeMap<Integer, TreeMap<Long, Long>> bids = new TreeMap<>();
  private final TreeMap<Integer, TreeMap<Long, Long>> asks = new TreeMap<>();

  private NavigableMap<Long, Long> side(int instrument, Side s) {
    return (s == Side.BUY ? bids : asks)
        .computeIfAbsent(
            instrument,
            i -> s == Side.BUY ? new TreeMap<>(Collections.reverseOrder()) : new TreeMap<>());
  }

  /** Applies an absolute level update; {@code aggQty == 0} removes the level. */
  public void apply(int instrument, Side s, long price, long aggQty) {
    NavigableMap<Long, Long> m = side(instrument, s);
    if (aggQty == 0) {
      m.remove(price);
    } else {
      m.put(price, aggQty);
    }
  }

  /** Replaces one instrument's whole book. */
  public void replace(int instrument, List<Level> bidLevels, List<Level> askLevels) {
    NavigableMap<Long, Long> b = side(instrument, Side.BUY);
    NavigableMap<Long, Long> a = side(instrument, Side.SELL);
    b.clear();
    a.clear();
    bidLevels.forEach(l -> b.put(l.price(), l.qty()));
    askLevels.forEach(l -> a.put(l.price(), l.qty()));
  }

  /** Up to {@code n} best levels; {@code n <= 0} means all. */
  public List<Level> levels(int instrument, Side s, int n) {
    List<Level> out = new ArrayList<>();
    for (Map.Entry<Long, Long> e : side(instrument, s).entrySet()) {
      if (n > 0 && out.size() == n) {
        break;
      }
      out.add(new Level(e.getKey(), e.getValue()));
    }
    return out;
  }

  /** Instruments that have ever had a level, ascending. */
  public List<Integer> instruments() {
    TreeMap<Integer, Boolean> all = new TreeMap<>();
    bids.keySet().forEach(i -> all.put(i, true));
    asks.keySet().forEach(i -> all.put(i, true));
    return List.copyOf(all.keySet());
  }
}
