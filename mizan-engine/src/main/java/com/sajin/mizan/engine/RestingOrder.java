package com.sajin.mizan.engine;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;

/** Immutable snapshot of a resting order, for views and tests. */
public record RestingOrder(
    long id, long account, int instrument, Side side, TimeInForce tif, long price, long remaining) {

  static RestingOrder of(Order o) {
    return new RestingOrder(
        o.id(), o.accountId(), o.instrument(), o.side(), o.tif(), o.limitPrice(), o.remaining());
  }
}
