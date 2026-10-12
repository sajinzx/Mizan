package com.sajin.mizan.sim.bft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.chain.bft.SafetyStore;
import com.sajin.mizan.testkit.Seed;
import com.sajin.mizan.testkit.SeedExtension;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * M8-T7: the fault matrix with a rotating proposer {@code (h + r) mod n}. Every scenario must stay
 * safe (no conflicting commits) and live (every honest validator advances {@value
 * ConsensusSweep#PROGRESS_AFTER_HEAL} heights after the last fault heals).
 */
@ExtendWith(SeedExtension.class)
class FaultMatrixTest {

  enum Fault {
    /** The current proposer crashes at a chosen phase of its round, then restarts. */
    PROPOSER_CRASH,
    /** {0,1} | {2,3}: no quorum on either side; heal after a few seconds. */
    PARTITION_2_2,
    /** Validator 3 joins late with an empty chain and must catch up by bulk sync. */
    LATE_JOINER,
    /** 10–30% loss, 5% duplication, 5–50 ms jitter (reordering), then heal. */
    LOSS,
    /** Validator 3 double-votes for the whole run. */
    BYZANTINE
  }

  record Result(
      Fault fault,
      long seed,
      String conflict,
      long minHeight,
      long required,
      long synced,
      int phase) {
    boolean ok() {
      return conflict == null && minHeight >= required;
    }
  }

  static Result run(Fault fault, long seed) {
    SimWorld world = new SimWorld(seed, 4);
    SimCluster c =
        new SimCluster(world, SimCluster.Params.rotating(4), i -> new SafetyStore.InMemory());
    SplittableRandom rnd = new SplittableRandom(seed * 31 + fault.name().hashCode());
    SimNetwork net = c.network();
    net.setFaults(0, 0, 1, 5);
    long faultEnd;
    int[] phase = {-1};
    switch (fault) {
      case PROPOSER_CRASH -> {
        long armAt = 500L + rnd.nextInt(3_000);
        int delay =
            new int[] {0, 1, 2, 3, 5, 8, 12}[rnd.nextInt(7)]; // ~ propose / prevote / precommit
        phase[0] = delay;
        long downFor = 300L + rnd.nextInt(3_000);
        boolean[] armed = {false};
        world.at(armAt, () -> armed[0] = true);
        c.onRoundStarted(
            (validator, h, r) -> {
              if (armed[0] && validator == c.validatorSet().proposer(h, r)) {
                armed[0] = false;
                long t = world.now() + delay;
                world.at(t, () -> c.crash(validator));
                world.at(t + downFor, () -> c.restart(validator));
              }
            });
        faultEnd =
            armAt + 20_000; // the crash happens shortly after armAt; restart well before this
      }
      case PARTITION_2_2 -> {
        long start = 500L + rnd.nextInt(2_000);
        long end = start + 1_000 + rnd.nextInt(4_000);
        world.at(start, () -> net.partition(0, 0, 1, 1));
        world.at(end, net::heal);
        faultEnd = end;
      }
      case LATE_JOINER -> {
        world.at(0, () -> c.crash(3));
        long join = 3_000L + rnd.nextInt(5_000);
        world.at(join, () -> c.restart(3));
        faultEnd = join;
      }
      case LOSS -> {
        net.setFaults(0.10 + rnd.nextDouble() * 0.20, 0.05, 5, 50);
        world.at(ConsensusSweep.NOISE_MS, net::heal);
        faultEnd = ConsensusSweep.NOISE_MS;
      }
      case BYZANTINE -> {
        c.makeByzantine(3);
        faultEnd = 5_000;
      }
      default -> throw new IllegalStateException();
    }
    long[] heightAtHeal = {-1};
    world.at(faultEnd, () -> heightAtHeal[0] = ConsensusSweep.maxHonestHeight(c));
    c.start();
    world.runUntil(
        ConsensusSweep.MAX_MS,
        () ->
            c.conflict() != null
                || (heightAtHeal[0] >= 0
                    && c.minHonestHeight()
                        >= heightAtHeal[0] + ConsensusSweep.PROGRESS_AFTER_HEAL));
    long required = Math.max(1, heightAtHeal[0] + ConsensusSweep.PROGRESS_AFTER_HEAL);
    return new Result(
        fault, seed, c.conflict(), c.minHonestHeight(), required, c.syncedBlocks(), phase[0]);
  }

  @Test
  void fewSeedsOfEveryFault(Seed seed) {
    SplittableRandom rnd = seed.random();
    for (int k = 0; k < 4; k++) {
      long s = rnd.nextLong();
      for (Fault f : Fault.values()) {
        Result r = run(f, s);
        assertThat(r.conflict()).as("%s seed %d", f, s).isNull();
        assertThat(r.minHeight())
            .as("%s seed %d liveness", f, s)
            .isGreaterThanOrEqualTo(r.required());
      }
    }
  }

  @Test
  void lateJoinerCatchesUpByBulkSync(Seed seed) {
    Result r = run(Fault.LATE_JOINER, seed.value());
    assertThat(r.ok()).isTrue();
    assertThat(r.synced()).as("blocks applied through SYNC_RESP").isPositive();
  }

  /** Gate M8: {@code -Pseeds=N} seeds × every fault. */
  @Test
  @Tag("sim")
  void matrix() {
    int seeds = Integer.getInteger("mizan.seeds", 100);
    Map<Fault, Integer> passed = new EnumMap<>(Fault.class);
    Map<Integer, Integer> crashPhases = new java.util.TreeMap<>();
    long synced = 0;
    List<String> failures = new ArrayList<>();
    for (long s = 1; s <= seeds; s++) {
      for (Fault f : Fault.values()) {
        Result r = run(f, s);
        if (r.ok()) {
          passed.merge(f, 1, Integer::sum);
        } else {
          failures.add(
              f
                  + " seed "
                  + s
                  + " conflict="
                  + r.conflict()
                  + " height="
                  + r.minHeight()
                  + "/"
                  + r.required());
        }
        if (f == Fault.PROPOSER_CRASH) {
          crashPhases.merge(r.phase(), 1, Integer::sum);
        }
        if (f == Fault.LATE_JOINER) {
          synced += r.synced();
        }
      }
    }
    System.out.printf(
        "fault matrix (rotating proposer): %d seeds; passed %s; proposer-crash delay after round start (ms) -> runs %s; "
            + "late-joiner blocks synced %d%n",
        seeds, passed, crashPhases, synced);
    failures.stream().limit(20).forEach(System.out::println);
    assertThat(failures).isEmpty();
  }
}
