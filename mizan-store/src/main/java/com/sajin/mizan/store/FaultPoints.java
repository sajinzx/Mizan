package com.sajin.mizan.store;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Named crash points for recovery tests (§9). Disabled unless the system property {@code
 * mizan.fault} is set to {@code <name>:<n>}: the {@code n}-th time point {@code <name>} is reached,
 * the JVM halts immediately (no shutdown hooks, no flushing), like {@code kill -9}.
 *
 * <p>When the property is absent, {@link #hit} is one volatile read and a string comparison, and
 * nothing else changes.
 */
public final class FaultPoints {
  public static final String BEFORE_APPEND = "before-append";
  public static final String MID_APPEND = "mid-append";
  public static final String AFTER_APPEND = "after-append-before-snapshot";
  public static final String MID_SNAPSHOT = "mid-snapshot";
  public static final String MID_BLOCK = "mid-block";

  private static final String NAME;
  private static final long AT;
  private static final AtomicLong COUNT = new AtomicLong();

  static {
    String spec = System.getProperty("mizan.fault");
    String name = null;
    long at = Long.MAX_VALUE;
    if (spec != null && spec.contains(":")) {
      name = spec.substring(0, spec.indexOf(':'));
      at = Long.parseLong(spec.substring(spec.indexOf(':') + 1).trim());
    }
    NAME = name;
    AT = at;
  }

  private FaultPoints() {}

  /** Whether {@code name} is the armed point and this is the fatal occurrence. */
  public static boolean due(String name) {
    return NAME != null && NAME.equals(name) && COUNT.incrementAndGet() >= AT;
  }

  /** Halts the JVM if {@code name} is due. */
  public static void hit(String name) {
    if (due(name)) {
      crash();
    }
  }

  public static void crash() {
    Runtime.getRuntime().halt(137);
  }
}
