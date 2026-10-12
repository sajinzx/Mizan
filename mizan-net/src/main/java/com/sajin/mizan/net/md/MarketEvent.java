package com.sajin.mizan.net.md;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;

/** Engine events forwarded to the market-data thread (M5-T1). */
public sealed interface MarketEvent {
  record Delta(int instrument, Side side, long price, long aggQty) implements MarketEvent {}

  record Traded(Trade trade) implements MarketEvent {}

  record Revoked(long fromTradeSeq) implements MarketEvent {}
}
