package com.sajin.mizan.sim.bft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.chain.bft.SafetyState;
import com.sajin.mizan.chain.bft.SafetyStore;
import com.sajin.mizan.sim.bft.ConsensusSweep.Outcome;
import com.sajin.mizan.sim.bft.ConsensusSweep.Scenario;
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
 * M7-T5 / M7-T9: consensus safety and liveness in the simulator. A few seeds run in every build;
 * {@code ./gradlew simSweep -Pseeds=1000} runs the full matrix (Gate M7).
 */
@ExtendWith(SeedExtension.class)
class ConsensusSimTest {

  static void assertSafeAndLive(Outcome o) {
    String where = o.scenario() + " seed " + o.seed();
    assertThat(o.conflict()).as(where + ": conflicting commits").isNull();
    assertThat(o.live())
        .as(
            where
                + ": liveness after noise stops (height "
                + o.minHeight()
                + " < "
                + o.requiredHeight()
                + ")")
        .isTrue();
  }

  @Test
  void fewSeedsOfEveryScenario(Seed seed) {
    SplittableRandom rnd = seed.random();
    for (int k = 0; k < 5; k++) {
      long s = rnd.nextLong();
      for (Scenario sc : Scenario.values()) {
        Outcome o = ConsensusSweep.run(sc, s, SimCluster.Params.fixedProposer(4), true);
        assertSafeAndLive(o);
        if (sc != Scenario.BYZANTINE) {
          assertThat(o.evidence())
              .as("%s seed %d: honest validators never equivocate", sc, s)
              .isEmpty();
        }
      }
    }
  }

  @Test
  void byzantineVoterIsDetected(Seed seed) {
    Outcome o =
        ConsensusSweep.run(
            Scenario.BYZANTINE, seed.value(), SimCluster.Params.fixedProposer(4), true);
    assertSafeAndLive(o);
    assertThat(o.evidence()).isNotEmpty().allMatch(e -> e.contains("validator 3"));
  }

  /** M7-T5: a validator restarted at arbitrary protocol steps never signs a conflicting vote. */
  @Test
  void restartsNeverProduceConflictingVotes(Seed seed) {
    SplittableRandom rnd = seed.random();
    for (int k = 0; k < 20; k++) {
      long s = rnd.nextLong();
      Outcome o =
          ConsensusSweep.run(Scenario.RESTARTS, s, SimCluster.Params.fixedProposer(4), true);
      assertSafeAndLive(o);
      assertThat(o.evidence()).as("seed %d", s).isEmpty();
    }
  }

  /** Records what was saved but "forgets" on load: models a validator without a consensus WAL. */
  static final class Amnesiac implements SafetyStore {
    SafetyState saved;

    @Override
    public SafetyState load() {
      return null;
    }

    @Override
    public void save(SafetyState s) {
      saved = s;
    }
  }

  /**
   * Targeted restart: validator 3 is down and validator 1 cannot hear the proposer, so 1 prevotes
   * nil and no round can decide without it. Validator 1 is crashed right after that nil prevote,
   * reconnected and restarted while the proposal is still fresh. With the WAL it re-sends its nil
   * prevote and the cluster recovers; without it (negative control) it prevotes the block in the
   * same round, and the other validators record the equivocation.
   */
  @Test
  void walPreventsEquivocationAfterRestart(Seed seed) {
    assertThat(restartAfterNilPrevote(seed.value(), true)).as("with WAL").isEmpty();
    assertThat(restartAfterNilPrevote(seed.value(), false)).as("without WAL").isNotEmpty();
  }

  static List<String> restartAfterNilPrevote(long seed, boolean wal) {
    SimWorld world = new SimWorld(seed, 4);
    SafetyStore[] stores = new SafetyStore[4];
    SimCluster c =
        new SimCluster(
            world,
            SimCluster.Params.fixedProposer(4),
            i -> stores[i] = (i == 1 && !wal) ? new Amnesiac() : new SafetyStore.InMemory());
    c.network().cut(0, 1);
    c.start();
    world.at(0, () -> c.crash(3));
    // Run until validator 1 has saved a nil prevote in some round >= 1.
    world.runUntil(
        60_000,
        () -> {
          SafetyState st = stores[1] instanceof Amnesiac a ? a.saved : stores[1].load();
          return st != null && st.prevote() != null && st.prevote().hash() == null;
        });
    c.crash(1);
    c.network().restore(0, 1);
    long t = world.now();
    world.at(t + 1, () -> c.restart(1));
    world.runUntil(t + 30_000, () -> false);
    assertThat(c.conflict()).isNull();
    if (wal) {
      assertThat(c.minHonestHeight()).as("progress resumes after the restart").isPositive();
    }
    return c.evidence();
  }

  /** Gate M7: {@code -Pseeds=N} seeds × every scenario. */
  @Test
  @Tag("sim")
  void sweep() {
    int seeds = Integer.getInteger("mizan.seeds", 100);
    Map<Scenario, Integer> passed = new EnumMap<>(Scenario.class);
    Map<Scenario, Long> messages = new EnumMap<>(Scenario.class);
    List<String> failures = new ArrayList<>();
    long evidenceCount = 0;
    for (long s = 1; s <= seeds; s++) {
      for (Scenario sc : Scenario.values()) {
        Outcome o = ConsensusSweep.run(sc, s, SimCluster.Params.fixedProposer(4), true);
        boolean ok = o.safe() && o.live() && (sc == Scenario.BYZANTINE || o.evidence().isEmpty());
        if (ok) {
          passed.merge(sc, 1, Integer::sum);
        } else {
          failures.add(
              sc
                  + " seed "
                  + s
                  + " conflict="
                  + o.conflict()
                  + " height="
                  + o.minHeight()
                  + " evidence="
                  + o.evidence().size()
                  + "  replay: ./gradlew :mizan-sim:simSweep -Pseeds="
                  + s);
        }
        messages.merge(sc, o.messages(), Long::sum);
        evidenceCount += sc == Scenario.BYZANTINE ? o.evidence().size() : 0;
      }
    }
    System.out.printf(
        "consensus sweep: %d seeds; passed per scenario %s; messages %s; Byzantine equivocations logged %d%n",
        seeds, passed, messages, evidenceCount);
    failures.stream().limit(20).forEach(System.out::println);
    assertThat(failures).isEmpty();
  }
}
