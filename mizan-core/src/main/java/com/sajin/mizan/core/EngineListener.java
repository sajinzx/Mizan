package com.sajin.mizan.core;

/** Full engine event stream. Called synchronously on the engine's thread; must not block. */
public interface EngineListener extends TradeListener {
  default void onAccepted(long account, long clientOrderId, long orderId) {}

  /** A resting order left the book: user cancel or self-trade prevention ({@code "STP"}). */
  default void onCancelled(long orderId, String reason) {}

  default void onBookDelta(int instrument, Side side, long price, long aggQty) {}

  /** Trades with {@code seq >= fromTradeSeq} were provisional and are revoked. */
  default void onRevoke(long fromTradeSeq) {}
}
