package com.sajin.mizan.sim.bft;

import com.sajin.mizan.chain.bft.SafetyState;
import com.sajin.mizan.chain.bft.SafetyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** One seeded consensus scenario run and its outcome (shared by the sweep tests). */
final class ConsensusSweep {
  enum Scenario {
    HAPPY,
    CRASH,
    BYZANTINE,
    LOSS,
    RESTARTS
  }

  record Outcome(
      Scenario scenario,
      long seed,
      String conflict,
      long minHeight,
      long requiredHeight,
      List<String> evidence,
      long messages,
      long virtualMs) {
    boolean safe() {
      return conflict == null;
    }

    /**
     * Every honest validator progressed {@code PROGRESS_AFTER_HEAL} heights after the last fault.
     */
    boolean live() {
      return minHeight >= requiredHeight;
    }
  }

  static final long TARGET = 12;
  static final long NOISE_MS = 10_000;
  static final long MAX_MS = 120_000;

  private ConsensusSweep() {}

  /** A safety store that forgets everything: the negative control for the WAL test. */
  static SafetyStore amnesiac() {
    return new SafetyStore() {
      @Override
      public SafetyState load() {
        return null;
      }

      @Override
      public void save(SafetyState s) {}
    };
  }

  /** Heights every honest validator must add after the last fault has healed. */
  static final long PROGRESS_AFTER_HEAL = 5;

  static Outcome run(Scenario sc, long seed, SimCluster.Params params, boolean persistSafety) {
    SimWorld world = new SimWorld(seed, params.n());
    SimCluster c =
        new SimCluster(world, params, i -> persistSafety ? new SafetyStore.InMemory() : amnesiac());
    SplittableRandom rnd = new SplittableRandom(seed ^ 0x5DEECE66DL);
    SimNetwork net = c.network();
    net.setFaults(0, 0, 1, 5);
    long faultEnd;
    switch (sc) {
      case HAPPY -> faultEnd = 3_000;
      case CRASH -> {
        long down = 200L + rnd.nextInt(3_000);
        long up = down + 500L + rnd.nextInt(4_000);
        world.at(down, () -> c.crash(2));
        world.at(up, () -> c.restart(2));
        faultEnd = up;
      }
      case BYZANTINE -> {
        c.makeByzantine(3); // stays Byzantine for the whole run
        faultEnd = 5_000;
      }
      case LOSS -> {
        double loss = 0.10 + rnd.nextDouble() * 0.20;
        net.setFaults(loss, 0.05, 5, 50);
        world.at(NOISE_MS, net::heal);
        faultEnd = NOISE_MS;
      }
      case RESTARTS -> {
        // Validator 1 crashes and restarts over and over, at arbitrary points of the protocol.
        net.setFaults(0.05, 0.05, 1, 20);
        long t = 50;
        for (int k = 0; k < 40; k++) {
          long down = t + rnd.nextInt(150);
          long up = down + 1 + rnd.nextInt(200);
          world.at(down, () -> c.crash(1));
          world.at(up, () -> c.restart(1));
          t = up;
        }
        world.at(t + 1, net::heal);
        faultEnd = t + 1;
      }
      default -> throw new IllegalStateException();
    }
    long[] heightAtHeal = {-1};
    world.at(faultEnd, () -> heightAtHeal[0] = maxHonestHeight(c));
    c.start();
    world.runUntil(
        MAX_MS,
        () ->
            c.conflict() != null
                || (heightAtHeal[0] >= 0
                    && c.minHonestHeight()
                        >= Math.max(TARGET, heightAtHeal[0] + PROGRESS_AFTER_HEAL)));
    long required = Math.max(TARGET, heightAtHeal[0] + PROGRESS_AFTER_HEAL);
    return new Outcome(
        sc,
        seed,
        c.conflict(),
        c.minHonestHeight(),
        required,
        new ArrayList<>(c.evidence()),
        net.sent(),
        world.now());
  }

  static long maxHonestHeight(SimCluster c) {
    long max = 0;
    for (SimCluster.Validator v : c.validators()) {
      max = Math.max(max, v.chain().size());
    }
    return max;
  }
}
