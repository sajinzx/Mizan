package com.sajin.mizan.core;

/** Order type. Ordinal values are part of the wire format (§5.3); never reorder. */
public enum OrderType {
  LIMIT,
  MARKET
}
