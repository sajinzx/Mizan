package com.sajin.mizan.core;

/** Time in force. {@link #code()} is the frozen wire value (§5.3). */
public enum TimeInForce {
  GTC(0),
  IOC(1),
  FOK(2),
  POST_ONLY(3);

  private final int code;

  TimeInForce(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  /** Returns the time in force for a wire code, or {@code null} if the code is unknown. */
  public static TimeInForce fromCode(int code) {
    return switch (code) {
      case 0 -> GTC;
      case 1 -> IOC;
      case 2 -> FOK;
      case 3 -> POST_ONLY;
      default -> null;
    };
  }
}
