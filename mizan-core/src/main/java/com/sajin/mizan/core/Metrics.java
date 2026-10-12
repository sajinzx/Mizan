package com.sajin.mizan.core;

import java.util.function.LongSupplier;

/**
 * A minimal metrics SPI (M9-T1). Components look up their counters and timers once, then record on
 * the hot path without map lookups. The node backs this with {@code LongAdder}s and HdrHistogram;
 * tests and the pure engine use {@link #NOOP}.
 */
public interface Metrics {
  /** A monotonically increasing count. */
  @FunctionalInterface
  interface Counter {
    void add(long n);

    default void inc() {
      add(1);
    }
  }

  /** A latency distribution, recorded in nanoseconds. */
  @FunctionalInterface
  interface Timer {
    void recordNanos(long nanos);
  }

  Counter counter(String name);

  Timer timer(String name);

  /** Registers a value read on every scrape (queue depths). */
  void gauge(String name, LongSupplier value);

  Metrics NOOP =
      new Metrics() {
        @Override
        public Counter counter(String name) {
          return n -> {};
        }

        @Override
        public Timer timer(String name) {
          return nanos -> {};
        }

        @Override
        public void gauge(String name, LongSupplier value) {}
      };
}
