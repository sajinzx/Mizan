package com.sajin.mizan.engine;

import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;

/**
 * Market order, always {@code IOC}; its price is a collar. A market sell escrows its base upfront.
 * A market buy pays from free quote as it fills, limited by {@link FillGuard}.
 */
public final class MarketOrder extends Order {

  public MarketOrder(long id, long accountId, int instrument, Side side, long collar, long qty) {
    super(id, accountId, instrument, side, TimeInForce.IOC, collar, qty);
  }

  @Override
  public OrderType type() {
    return OrderType.MARKET;
  }

  @Override
  public boolean rests() {
    return false;
  }

  @Override
  public boolean escrowsUpfront() {
    return side() == Side.SELL;
  }

  @Override
  MarketOrder copy() {
    return new MarketOrder(id(), accountId(), instrument(), side(), limitPrice(), remaining());
  }
}
