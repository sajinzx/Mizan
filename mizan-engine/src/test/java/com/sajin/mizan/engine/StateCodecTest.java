package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.CommandStreams;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.Seed;
import com.sajin.mizan.testkit.SeedExtension;
import com.sajin.mizan.testkit.StateDump;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** M2-T8: canonical state serialization. */
@ExtendWith(SeedExtension.class)
class StateCodecTest {

  static ExecutionCore decode(byte[] b) throws ProtocolException {
    return StateCodec.decode(b, Fixtures.genesis(), CommandSigner.FAST_VERIFIER);
  }

  /** A core after a random stream, plus the commands for continuing it. */
  record Run(ExecutionCore core, CommandStreams.Generator gen) {}

  static Run randomCore(SplittableRandom rnd, int ops) {
    ExecutionCore core = ExecutionCore.genesis(Fixtures.genesis(), CommandSigner.FAST_VERIFIER);
    var gen = new CommandStreams.Generator(CommandSigner.FAST);
    gen.setup().forEach(c -> core.execute(c, ExecutionCore.SILENT));
    for (int i = 0; i < ops; i++) {
      core.execute(
          gen.next(CommandStreams.randomOp(rnd), () -> EngineProperties.resting(core)),
          ExecutionCore.SILENT);
    }
    return new Run(core, gen);
  }

  @Test
  void roundTripAfterRandomStreams(Seed seed) throws ProtocolException {
    SplittableRandom rnd = seed.random();
    for (int s = 0; s < 100; s++) {
      Run run = randomCore(rnd, rnd.nextInt(0, 200));
      byte[] bytes = StateCodec.encode(run.core());
      ExecutionCore decoded = decode(bytes);
      assertThat(decoded.stateRoot()).isEqualTo(run.core().stateRoot());
      assertThat(StateDump.of(decoded)).isEqualTo(StateDump.of(run.core()));
      assertThat(StateCodec.encode(decoded)).isEqualTo(bytes);

      // The decoded core behaves identically from here on.
      for (int i = 0; i < 30; i++) {
        SignedCommand c =
            run.gen()
                .next(CommandStreams.randomOp(rnd), () -> EngineProperties.resting(run.core()));
        assertThat(decoded.execute(c, ExecutionCore.SILENT))
            .isEqualTo(run.core().execute(c, ExecutionCore.SILENT));
      }
      assertThat(decoded.stateRoot()).isEqualTo(run.core().stateRoot());
    }
  }

  @Test
  void everyTruncationAndExtensionIsRejected(Seed seed) {
    byte[] bytes = StateCodec.encode(randomCore(seed.random(), 60).core());
    for (int len = 0; len < bytes.length; len++) {
      byte[] cut = Arrays.copyOf(bytes, len);
      assertThatThrownBy(() -> decode(cut)).isInstanceOf(ProtocolException.class);
    }
    assertThatThrownBy(() -> decode(Arrays.copyOf(bytes, bytes.length + 1)))
        .isInstanceOf(ProtocolException.class);
  }

  @Test
  void mutatedSnapshotsDecodeOrFailOnlyWithProtocolException(Seed seed) {
    SplittableRandom rnd = seed.random();
    byte[] bytes = StateCodec.encode(randomCore(rnd, 80).core());
    int decoded = 0;
    for (int i = 0; i < 20_000; i++) {
      byte[] m = bytes.clone();
      m[rnd.nextInt(m.length)] ^= (byte) (1 << rnd.nextInt(8));
      try {
        ExecutionCore c = decode(m);
        assertThat(StateCodec.encode(c)).isEqualTo(m); // whatever decodes is canonical
        decoded++;
      } catch (ProtocolException expected) {
        // fine
      }
    }
    System.out.println("state codec fuzz: 20000 mutations, " + decoded + " decoded");
  }

  @Test
  void rejectsStructuralErrors() {
    ExecutionCore core = TestCore.funded().core;
    byte[] bytes = StateCodec.encode(core);
    byte[] badVersion = bytes.clone();
    badVersion[0] = 2;
    assertThatThrownBy(() -> decode(badVersion)).hasMessageContaining("version");
    byte[] zeroCounter = bytes.clone();
    Arrays.fill(zeroCounter, 1, 9, (byte) 0);
    assertThatThrownBy(() -> decode(zeroCounter)).hasMessageContaining("counters");

    List<com.sajin.mizan.core.Instrument> one =
        new ArrayList<>(Fixtures.genesis().instruments().subList(0, 1));
    var g = Fixtures.genesis();
    var fewer =
        new com.sajin.mizan.core.GenesisConfig(
            g.chainId(),
            1,
            g.adminPubKey(),
            g.assets(),
            one,
            g.validators(),
            g.block(),
            g.consensus());
    assertThatThrownBy(() -> StateCodec.decode(bytes, fewer, CommandSigner.FAST_VERIFIER))
        .hasMessageContaining("book count");
  }
}
