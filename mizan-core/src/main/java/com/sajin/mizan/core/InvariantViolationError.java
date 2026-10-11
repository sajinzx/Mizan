package com.sajin.mizan.core;

/** An internal invariant is broken: a bug. Never caught by the engine; the node halts (§2). */
public final class InvariantViolationError extends Error {
  private static final long serialVersionUID = 1L;

  public InvariantViolationError(String message) {
    super(message);
  }
}
