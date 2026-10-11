package com.sajin.mizan.core;

import java.io.IOException;

/** Durable storage failed or holds corrupt data. */
public class PersistenceException extends IOException {
  private static final long serialVersionUID = 1L;

  public PersistenceException(String message) {
    super(message);
  }

  public PersistenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
