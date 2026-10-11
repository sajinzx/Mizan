package com.sajin.mizan.core;

/** Order type. {@link #code()} is the frozen wire value (§5.3). */
public enum OrderType {
  LIMIT(0),
  MARKET(1);

  private final int code;

  OrderType(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  /** Returns the type for a wire code, or {@code null} if the code is unknown. */
  public static OrderType fromCode(int code) {
    return switch (code) {
      case 0 -> LIMIT;
      case 1 -> MARKET;
      default -> null;
    };
  }
}
