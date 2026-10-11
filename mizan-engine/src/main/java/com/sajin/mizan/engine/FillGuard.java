package com.sajin.mizan.engine;

/** Caps how much a taker may fill at a given price (§6.2). */
@FunctionalInterface
public interface FillGuard {
  FillGuard UNLIMITED = (taker, px) -> Long.MAX_VALUE;

  /** Maximum quantity {@code taker} can afford at {@code px}; {@code 0} stops matching. */
  long maxQty(Order taker, long px);
}
