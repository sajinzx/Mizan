package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.CommandStreams;
import com.sajin.mizan.testkit.Fixtures;
import java.util.List;
import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * M10-T2: the incremental v2 root (dirty keys reported by the ledger and books) always equals v2
 * rebuilt from scratch. A missed dirty mark anywhere in the engine makes this fail.
 */
class SmtStateRootTest {

  static ExecutionCore v2() {
    return ExecutionCore.genesis(Fixtures.genesisV2(), CommandSigner.FAST_VERIFIER);
  }

  @Property(tries = 200)
  void incrementalEqualsFromScratch(
      @ForAll long seed,
      @ForAll @IntRange(min = 1, max = 300) int steps,
      @ForAll @IntRange(min = 1, max = 20) int rootEvery) {
    SplittableRandom rnd = new SplittableRandom(seed);
    ExecutionCore core = v2();
    var gen = new CommandStreams.Generator(CommandSigner.FAST);
    for (SignedCommand c : gen.setup()) {
      core.execute(c, ExecutionCore.SILENT);
    }
    ExecutionCore copy = null;
    for (int i = 0; i < steps; i++) {
      ExecutionCore at = core;
      core.execute(
          gen.next(CommandStreams.randomOp(rnd), () -> EngineProperties.resting(at)),
          ExecutionCore.SILENT);
      if (i % rootEvery == 0) { // batch sizes vary: dirty keys accumulate between roots
        assertThat(core.stateRoot()).as("step %d", i).isEqualTo(core.stateRootFromScratch());
      }
      if (i == steps / 2) {
        copy = core.copy(); // with pending dirty keys
      }
    }
    assertThat(core.stateRoot()).isEqualTo(core.stateRootFromScratch());
    if (copy != null) {
      assertThat(copy.stateRoot()).isEqualTo(copy.stateRootFromScratch());
    }
  }

  @Test
  void versionsDifferAndSnapshotsRoundTrip() throws Exception {
    ExecutionCore a = v2();
    ExecutionCore b = ExecutionCore.genesis(Fixtures.genesis(), CommandSigner.FAST_VERIFIER);
    var gen = new CommandStreams.Generator(CommandSigner.FAST);
    List<SignedCommand> setup = gen.setup();
    for (SignedCommand c : setup) {
      a.execute(c, ExecutionCore.SILENT);
      b.execute(c, ExecutionCore.SILENT);
    }
    SplittableRandom rnd = new SplittableRandom(4);
    for (int i = 0; i < 400; i++) {
      SignedCommand c = gen.next(CommandStreams.randomOp(rnd), () -> EngineProperties.resting(a));
      a.execute(c, ExecutionCore.SILENT);
      b.execute(c, ExecutionCore.SILENT);
    }
    assertThat(a.stateRoot())
        .as("v2 differs from v1 for the same state")
        .isNotEqualTo(b.stateRoot());
    ExecutionCore back =
        StateCodec.decode(StateCodec.encode(a), Fixtures.genesisV2(), CommandSigner.FAST_VERIFIER);
    assertThat(back.stateRoot()).isEqualTo(a.stateRoot());
    // A copy diverges independently of its original (persistent trees are shared, not aliased).
    ExecutionCore c = a.copy();
    SignedCommand next = gen.next(CommandStreams.randomOp(rnd), () -> EngineProperties.resting(c));
    c.execute(next, ExecutionCore.SILENT);
    assertThat(a.stateRoot()).isEqualTo(a.stateRootFromScratch());
    assertThat(c.stateRoot()).isEqualTo(c.stateRootFromScratch());
  }
}
