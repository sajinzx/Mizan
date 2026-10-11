package com.sajin.mizan.engine;

import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;

/** Limit order: escrowed upfront; rests if {@code GTC} or {@code POST_ONLY}. */
public final class LimitOrder extends Order {

  public LimitOrder(
      long id, long accountId, int instrument, Side side, TimeInForce tif, long price, long qty) {
    super(id, accountId, instrument, side, tif, price, qty);
  }

  @Override
  public OrderType type() {
    return OrderType.LIMIT;
  }

  @Override
  public boolean rests() {
    return tif() == TimeInForce.GTC || tif() == TimeInForce.POST_ONLY;
  }

  @Override
  public boolean escrowsUpfront() {
    return true;
  }

  @Override
  LimitOrder copy() {
    return new LimitOrder(
        id(), accountId(), instrument(), side(), tif(), limitPrice(), remaining());
  }
}
