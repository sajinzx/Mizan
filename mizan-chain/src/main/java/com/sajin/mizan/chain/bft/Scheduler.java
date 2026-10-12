package com.sajin.mizan.chain.bft;

/**
 * Runs tasks later on the consensus event loop (M7-T2). Tasks always run on the same single thread
 * that delivers messages, so consensus code needs no locking.
 */
public interface Scheduler extends Clock {

  /** A scheduled task that can be cancelled. */
  @FunctionalInterface
  interface Cancellable {
    void cancel();
  }

  Cancellable schedule(long delayMillis, Runnable task);
}
