package com.sajin.mizan.testkit;

import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;
import java.util.ArrayList;
import java.util.List;

/**
 * Records every engine event as a value object, in order. Reject reasons are free text and are not
 * part of the recorded value, so independent implementations can be compared.
 */
public final class RecordingListener implements EngineListener {

  /** Marker for recorded events. */
  public sealed interface Event permits Traded, Accepted, Cancelled, Delta, Rejected, Revoked {}

  public record Traded(Trade trade) implements Event {}

  public record Accepted(long account, long clientOrderId, long orderId) implements Event {}

  public record Cancelled(long orderId, String reason) implements Event {}

  public record Delta(int instrument, Side side, long price, long aggQty) implements Event {}

  public record Rejected(long account, long ref) implements Event {}

  public record Revoked(long fromTradeSeq) implements Event {}

  private final List<Event> events = new ArrayList<>();

  public List<Event> events() {
    return events;
  }

  /** Returns and clears the recorded events. */
  public List<Event> drain() {
    List<Event> out = List.copyOf(events);
    events.clear();
    return out;
  }

  public List<Trade> trades() {
    List<Trade> out = new ArrayList<>();
    for (Event e : events) {
      if (e instanceof Traded t) {
        out.add(t.trade());
      }
    }
    return out;
  }

  @Override
  public void onTrade(Trade t) {
    events.add(new Traded(t));
  }

  @Override
  public void onReject(long account, long ref, String reason) {
    events.add(new Rejected(account, ref));
  }

  @Override
  public void onAccepted(long account, long clientOrderId, long orderId) {
    events.add(new Accepted(account, clientOrderId, orderId));
  }

  @Override
  public void onCancelled(long orderId, String reason) {
    events.add(new Cancelled(orderId, reason));
  }

  @Override
  public void onBookDelta(int instrument, Side side, long price, long aggQty) {
    events.add(new Delta(instrument, side, price, aggQty));
  }

  @Override
  public void onRevoke(long fromTradeSeq) {
    events.add(new Revoked(fromTradeSeq));
  }
}
