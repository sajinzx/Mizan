package com.sajin.mizan.node;

import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.ResultListener;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.Trade;
import java.util.List;

/** Fan-out composition of listeners, called in order on the caller's thread. */
final class Listeners {
  private Listeners() {}

  static EngineListener engine(List<EngineListener> ls) {
    List<EngineListener> all = List.copyOf(ls);
    return new EngineListener() {
      @Override
      public void onTrade(Trade t) {
        all.forEach(l -> l.onTrade(t));
      }

      @Override
      public void onReject(long account, long ref, String reason) {
        all.forEach(l -> l.onReject(account, ref, reason));
      }

      @Override
      public void onAccepted(long account, long clientOrderId, long orderId) {
        all.forEach(l -> l.onAccepted(account, clientOrderId, orderId));
      }

      @Override
      public void onCancelled(long orderId, String reason) {
        all.forEach(l -> l.onCancelled(orderId, reason));
      }

      @Override
      public void onBookDelta(int instrument, Side side, long price, long aggQty) {
        all.forEach(l -> l.onBookDelta(instrument, side, price, aggQty));
      }

      @Override
      public void onRevoke(long fromTradeSeq) {
        all.forEach(l -> l.onRevoke(fromTradeSeq));
      }
    };
  }

  static ResultListener results(List<ResultListener> ls) {
    List<ResultListener> all = List.copyOf(ls);
    return (SignedCommand c, ExecResult r) -> all.forEach(l -> l.onResult(c, r));
  }

  static FinalityListener finality(List<FinalityListener> ls) {
    List<FinalityListener> all = List.copyOf(ls);
    return (height, cmds, results) -> all.forEach(l -> l.onFinal(height, cmds, results));
  }
}
