package com.sajin.mizan.testkit;

import java.util.SplittableRandom;

/**
 * The single root seed of a randomized test (§9). Inject it with {@link SeedExtension}; derive all
 * randomness from {@link #random()} so a failure replays exactly with {@code -Pseed=<value>}.
 */
public record Seed(long value) {
  public SplittableRandom random() {
    return new SplittableRandom(value);
  }
}
