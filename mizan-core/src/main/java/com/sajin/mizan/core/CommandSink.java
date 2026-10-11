package com.sajin.mizan.core;

/** Entry point into the sequencer. Never blocks. */
public interface CommandSink {
  /**
   * Returns {@code false} if the inbound queue is full (the gateway answers {@code BUSY}); nothing
   * was executed.
   */
  boolean submit(SignedCommand c);
}
