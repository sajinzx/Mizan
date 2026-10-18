package com.sajin.mizan.ui;

import com.sajin.mizan.node.SandboxView;
import java.util.ArrayList;
import java.util.List;

/** Re-buckets one-second candles into wider bars for the price chart. */
public final class CandleSeries {
  private CandleSeries() {}

  /** The last {@code maxBars} bars of {@code seconds}-second width, oldest first. */
  public static List<SandboxView.Candle> aggregate(
      List<SandboxView.Candle> oneSecond, int seconds, int maxBars) {
    long width = seconds * 1_000L;
    List<SandboxView.Candle> out = new ArrayList<>();
    long start = Long.MIN_VALUE;
    long o = 0;
    long h = 0;
    long l = 0;
    long c = 0;
    long v = 0;
    int n = 0;
    for (SandboxView.Candle k : oneSecond) {
      long s = Math.floorDiv(k.startMillis(), width) * width;
      if (s != start) {
        if (start != Long.MIN_VALUE) {
          out.add(new SandboxView.Candle(start, o, h, l, c, v, n));
        }
        start = s;
        o = k.open();
        h = k.high();
        l = k.low();
        v = 0;
        n = 0;
      }
      h = Math.max(h, k.high());
      l = Math.min(l, k.low());
      c = k.close();
      v += k.volume();
      n += k.trades();
    }
    if (start != Long.MIN_VALUE) {
      out.add(new SandboxView.Candle(start, o, h, l, c, v, n));
    }
    return out.size() <= maxBars ? out : List.copyOf(out.subList(out.size() - maxBars, out.size()));
  }
}
