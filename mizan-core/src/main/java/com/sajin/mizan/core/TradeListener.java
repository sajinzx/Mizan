package com.sajin.mizan.core;

/** Receives fills and rejects from the engine, on the engine's thread. */
public interface TradeListener {
  void onTrade(Trade t);

  default void onReject(long account, long ref, String reason) {}
}
