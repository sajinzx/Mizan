package com.sajin.mizan.core;

/** Order side. Ordinal values are part of the wire format (§5.3); never reorder. */
public enum Side {
  BUY,
  SELL;

  public Side opposite() {
    return this == BUY ? SELL : BUY;
  }
}
