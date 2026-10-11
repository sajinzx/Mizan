package com.sajin.mizan.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.RestingOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@ExtendWith(SeedExtension.class)
class DifferentialTest {

  static List<RestingOrder> resting(ExecutionCore core) {
    List<RestingOrder> out = new ArrayList<>();
    for (int i : core.instrumentIds()) {
      for (var s : com.sajin.mizan.core.Side.values()) {
        out.addAll(core.restingOrders(i, s));
      }
    }
    return out;
  }

  /** Runs {@code streams} random streams of {@code length} ops; returns result-status counts. */
  static Map<ExecResult.Status, Integer> runStreams(
      SplittableRandom rnd, int streams, int length, ReferenceEngine.Bug bug) {
    Map<ExecResult.Status, Integer> counts = new EnumMap<>(ExecResult.Status.class);
    for (int s = 0; s < streams; s++) {
      Differential d = new Differential(Fixtures.genesis(), CommandSigner.FAST_VERIFIER, bug);
      var gen = new CommandStreams.Generator(CommandSigner.FAST);
      d.run(gen.setup());
      for (int i = 0; i < length; i++) {
        SignedCommand c = gen.next(CommandStreams.randomOp(rnd), () -> resting(d.core()));
        counts.merge(d.step(c).status(), 1, Integer::sum);
      }
    }
    return counts;
  }

  @Test
  void engineMatchesReferenceOnRandomStreams(Seed seed) {
    var counts = runStreams(seed.random(), 200, 150, ReferenceEngine.Bug.NONE);
    System.out.println("differential: 200 streams x 150 ops, statuses " + counts);
    assertThat(counts.get(ExecResult.Status.ACCEPTED)).isGreaterThan(10_000);
    assertThat(counts.get(ExecResult.Status.REJECTED)).isGreaterThan(1_000);
    assertThat(counts.get(ExecResult.Status.AUTH_FAILED)).isGreaterThan(500);
  }

  @ParameterizedTest
  @EnumSource(value = ReferenceEngine.Bug.class, mode = EnumSource.Mode.EXCLUDE, names = "NONE")
  void harnessCatchesInjectedBugs(ReferenceEngine.Bug bug) {
    assertThatThrownBy(() -> runStreams(new SplittableRandom(42), 200, 150, bug))
        .as("bug %s must be detected", bug)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("differ");
  }
}
