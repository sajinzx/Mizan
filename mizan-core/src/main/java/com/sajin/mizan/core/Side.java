package com.sajin.mizan.core;

/** Order side. {@link #code()} is the frozen wire value (§5.3). */
public enum Side {
  BUY(0),
  SELL(1);

  private final int code;

  Side(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  /** Returns the side for a wire code, or {@code null} if the code is unknown. */
  public static Side fromCode(int code) {
    return switch (code) {
      case 0 -> BUY;
      case 1 -> SELL;
      default -> null;
    };
  }

  public Side opposite() {
    return this == BUY ? SELL : BUY;
  }
}
