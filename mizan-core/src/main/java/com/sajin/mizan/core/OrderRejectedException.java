package com.sajin.mizan.core;

/**
 * Deterministic rejection, always thrown <em>before</em> any state mutation (§5.2). The same
 * command against the same state always produces the same rejection.
 */
public abstract sealed class OrderRejectedException extends RuntimeException
    permits InvalidOrderException,
        InsufficientBalanceException,
        LimitExceededException,
        InvalidSignatureException,
        StaleNonceException {
  private static final long serialVersionUID = 1L;

  protected OrderRejectedException(String message) {
    super(message, null, false, false); // no stack trace: rejects are expected, not exceptional
  }

  /** Stable wire code for the {@code REJECTED} frame's {@code reasonCode} field (§5.5). */
  public abstract int reasonCode();
}
