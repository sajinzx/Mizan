package com.sajin.mizan.engine;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;

/** Receives matching events, synchronously and in order, from {@link OrderBook#match}. */
public interface MatchSink {
  /** A fill. Called before the next fill is sized, so settlement can update balances. */
  void onFill(Trade t);

  /** Self-trade prevention removed the resting {@code maker} from the book. */
  void onSelfTradeCancel(Order maker);

  /** The aggregate quantity at {@code (side, price)} is now {@code aggQty}. */
  void onDelta(Side side, long price, long aggQty);
}
