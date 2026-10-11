package com.sajin.mizan.net;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Classic token bucket: {@code ratePerSecond} refill, {@code burst} capacity. Thread-safe. */
public final class TokenBucket {
  private final double ratePerNano;
  private final double burst;
  private final LongSupplier nanoClock;
  private final ReentrantLock lock = new ReentrantLock();
  private double tokens;
  private long last;

  public TokenBucket(double ratePerSecond, double burst, LongSupplier nanoClock) {
    if (ratePerSecond <= 0 || burst < 1) {
      throw new IllegalArgumentException("rate must be positive and burst >= 1");
    }
    this.ratePerNano = ratePerSecond / 1e9;
    this.burst = burst;
    this.nanoClock = nanoClock;
    this.tokens = burst;
    this.last = nanoClock.getAsLong();
  }

  /** Takes one token if available. */
  public boolean tryAcquire() {
    lock.lock();
    try {
      long now = nanoClock.getAsLong();
      tokens = Math.min(burst, tokens + (now - last) * ratePerNano);
      last = now;
      if (tokens >= 1) {
        tokens -= 1;
        return true;
      }
      return false;
    } finally {
      lock.unlock();
    }
  }
}
