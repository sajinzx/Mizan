package com.sajin.mizan.core;

/** Time in force. Ordinal values are part of the wire format (§5.3); never reorder. */
public enum TimeInForce {
  GTC,
  IOC,
  FOK,
  POST_ONLY
}
