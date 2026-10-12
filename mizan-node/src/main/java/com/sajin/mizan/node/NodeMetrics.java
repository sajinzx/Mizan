package com.sajin.mizan.node;

import com.sajin.mizan.chain.bft.RoundBft;
import com.sajin.mizan.core.Metrics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * The node's {@link Metrics} (M9-T1): {@link LongAdder} counters, HdrHistogram timers and gauges,
 * rendered as plain text by {@link #render()}. Timers record into a {@link Recorder} (wait-free on
 * the hot path); a scrape folds the interval histogram into a cumulative one, so quantiles cover
 * the node's whole life.
 */
public final class NodeMetrics implements Metrics {
  private static final double[] QUANTILES = {0.5, 0.99, 0.999};

  private final Map<String, LongAdder> counters = new ConcurrentSkipListMap<>();
  private final Map<String, TimerState> timers = new ConcurrentSkipListMap<>();
  private final Map<String, LongSupplier> gauges = new ConcurrentSkipListMap<>();

  private static final class TimerState {
    final Recorder recorder = new Recorder(3);
    final Histogram total = new Histogram(3);
    private Histogram interval;

    synchronized Histogram snapshot() {
      interval = recorder.getIntervalHistogram(interval);
      total.add(interval);
      return total.copy();
    }
  }

  @Override
  public Counter counter(String name) {
    LongAdder a = counters.computeIfAbsent(name, n -> new LongAdder());
    return a::add;
  }

  @Override
  public Timer timer(String name) {
    Recorder r = timers.computeIfAbsent(name, n -> new TimerState()).recorder;
    return nanos -> r.recordValue(Math.max(0, nanos));
  }

  @Override
  public void gauge(String name, LongSupplier value) {
    gauges.put(name, value);
  }

  /** Current value of a counter (0 if it was never registered). */
  public long count(String name) {
    LongAdder a = counters.get(name);
    return a == null ? 0 : a.sum();
  }

  /** A copy of a timer's cumulative histogram ({@code null} if unknown). */
  public Histogram histogram(String name) {
    TimerState t = timers.get(name);
    return t == null ? null : t.snapshot();
  }

  /**
   * Plain-text exposition, one {@code name value} per line, names prefixed {@code mizan_}. Timers
   * give count, p50/p99/p99.9 and max in microseconds (max is observed, not a bound).
   */
  public String render() {
    StringBuilder sb = new StringBuilder(4096);
    counters.forEach((n, a) -> line(sb, n + "_total", a.sum()));
    gauges.forEach(
        (n, g) -> {
          long v;
          try {
            v = g.getAsLong();
          } catch (RuntimeException e) {
            v = -1;
          }
          line(sb, n, v);
        });
    timers.forEach(
        (n, t) -> {
          Histogram h = t.snapshot();
          line(sb, n + "_count", h.getTotalCount());
          for (double q : QUANTILES) {
            line(sb, n + "_us{quantile=\"" + q + "\"}", h.getValueAtPercentile(q * 100) / 1_000);
          }
          line(sb, n + "_us_max", h.getMaxValue() / 1_000);
        });
    return sb.toString();
  }

  private static void line(StringBuilder sb, String name, long value) {
    sb.append("mizan_").append(name).append(' ').append(value).append('\n');
  }

  /**
   * Wraps a consensus observer with round-bump, decision and height-latency metrics. Rounds above 0
   * are "round bumps" (a timeout or a failed proposer); height latency runs from round 0 of a
   * height to its decision.
   */
  RoundBft.Observer observe(RoundBft.Observer inner) {
    Counter bumps = counter("consensus_round_bumps");
    Counter decided = counter("consensus_decided");
    Counter equivocations = counter("consensus_equivocations");
    Timer heightLatency = timer("consensus_height_latency");
    Map<Long, Long> started = new ConcurrentHashMap<>();
    return new RoundBft.Observer() {
      @Override
      public void equivocation(
          int validator, long height, int round, String kind, byte[] a, byte[] b) {
        equivocations.inc();
        inner.equivocation(validator, height, round, kind, a, b);
      }

      @Override
      public void roundStarted(long height, int round) {
        if (round > 0) {
          bumps.inc();
        } else {
          started.put(height, System.nanoTime());
        }
        inner.roundStarted(height, round);
      }

      @Override
      public void decided(long height, int round) {
        decided.inc();
        Long t0 = started.remove(height);
        if (t0 != null) {
          heightLatency.recordNanos(System.nanoTime() - t0);
        }
        started.keySet().removeIf(h -> h < height);
        inner.decided(height, round);
      }
    };
  }
}
