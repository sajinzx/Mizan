package com.sajin.mizan.core;

import java.util.Objects;

/** Places a new order. Business validation (ranges, tick/lot, TIF rules) belongs to the engine. */
public record Place(
    long clientOrderId,
    int instrument,
    Side side,
    OrderType type,
    TimeInForce tif,
    long price,
    long qty)
    implements OrderCommand {
  public Place {
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(tif, "tif");
  }
}
