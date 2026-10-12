package com.sajin.mizan.chain.bft;

import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Records engine events during a validation replay so they can be delivered later, once the block
 * is decided (committed trades and book deltas for gateways and market data), without executing the
 * block twice.
 */
public final class RecordedEvents implements EngineListener {
  private final List<Consumer<EngineListener>> events = new ArrayList<>();

  @Override
  public void onTrade(Trade t) {
    events.add(l -> l.onTrade(t));
  }

  @Override
  public void onCancelled(long orderId, String reason) {
    events.add(l -> l.onCancelled(orderId, reason));
  }

  @Override
  public void onBookDelta(int instrument, Side side, long price, long aggQty) {
    events.add(l -> l.onBookDelta(instrument, side, price, aggQty));
  }

  public void replayTo(EngineListener l) {
    events.forEach(e -> e.accept(l));
  }

  public int size() {
    return events.size();
  }
}
