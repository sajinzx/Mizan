package com.sajin.mizan.chain.bft;

/** Wall or virtual time in milliseconds. */
@FunctionalInterface
public interface Clock {
  long nowMillis();
}
