package com.sajin.mizan.core;

/**
 * Hard numeric limits (§5.1). {@code MAX_PRICE * MAX_QTY <= 2^62}, so one {@code multiplyExact} on
 * a single order cannot overflow. Balance accumulation uses {@code addExact}; overflow is a
 * deterministic {@link LimitExceededException}.
 */
public final class Limits {
  public static final long MAX_PRICE = 1L << 31;
  public static final long MAX_QTY = 1L << 31;
  public static final long MAX_DEPOSIT = 1L << 50;
  public static final long MAX_ACCOUNT = (1L << 47) - 1;
  public static final int MAX_ASSET = 0xFFFF;
  public static final int MAX_ENVELOPE_BYTES = 256;

  private Limits() {}
}
