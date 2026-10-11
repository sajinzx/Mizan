package com.sajin.mizan.core;

import java.util.Objects;

/**
 * Outcome of executing one command (§6.3).
 *
 * @param orderId the engine-assigned order id for an accepted {@code Place}, else {@code 0}
 * @param reasonCode stable reject code ({@link OrderRejectedException#reasonCode()}); {@code 0}
 *     when accepted
 * @param reason human-readable reject reason; {@code null} when accepted
 */
public record ExecResult(Status status, long orderId, int reasonCode, String reason) {

  /** {@code AUTH_FAILED} leaves state untouched; {@code REJECTED} burns only the nonce. */
  public enum Status {
    AUTH_FAILED,
    REJECTED,
    ACCEPTED
  }

  public ExecResult {
    Objects.requireNonNull(status, "status");
  }

  public static ExecResult accepted(long orderId) {
    return new ExecResult(Status.ACCEPTED, orderId, 0, null);
  }

  public static ExecResult rejected(int reasonCode, String reason) {
    return new ExecResult(Status.REJECTED, 0, reasonCode, reason);
  }

  public static ExecResult authFailed(int reasonCode, String reason) {
    return new ExecResult(Status.AUTH_FAILED, 0, reasonCode, reason);
  }
}
