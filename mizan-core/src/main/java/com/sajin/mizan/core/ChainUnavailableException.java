package com.sajin.mizan.core;

/** The chain cannot currently accept or finalize work (e.g. no quorum, node shutting down). */
public class ChainUnavailableException extends Exception {
  private static final long serialVersionUID = 1L;

  public ChainUnavailableException(String message) {
    super(message);
  }
}
