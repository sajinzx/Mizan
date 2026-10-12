package com.sajin.mizan.chain.bft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.chain.Block;
import com.sajin.mizan.chain.BlockHeader;
import com.sajin.mizan.chain.CommitCert;
import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.chain.Votes;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.store.ConsensusWal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M7-T3 (message codecs) and M7-T5 (safety state + WAL). */
class ConsensusMessageTest {
  @TempDir Path dir;

  static Block block() {
    byte[] z = new byte[32];
    return new Block(
        new BlockHeader(BlockHeader.VERSION, 4242, 3, 1, z, Block.txRoot(List.of()), z, 1, 99),
        List.of());
  }

  static byte[] sig(int fill) {
    byte[] s = new byte[64];
    Arrays.fill(s, (byte) fill);
    return s;
  }

  @Test
  void messagesRoundTrip() throws ProtocolException {
    Block b = block();
    List<ConsensusMessage> all =
        List.of(
            new ConsensusMessage.Proposal(3, 1, -1, 1, b, sig(1)),
            new ConsensusMessage.Vote(Votes.PREVOTE, 3, 1, b.hash(), 2, sig(2)),
            new ConsensusMessage.Vote(Votes.PRECOMMIT, 3, 1, null, 2, sig(3)),
            new ConsensusMessage.Commit(
                0,
                new CommittedBlock(b, new CommitCert(1, List.of(new CommitCert.Sig(0, sig(4)))))));
    for (ConsensusMessage m : all) {
      byte[] bytes = ConsensusMessage.encode(m);
      assertThat(ConsensusMessage.encode(ConsensusMessage.decode(bytes))).isEqualTo(bytes);
    }
  }

  @Test
  void malformedMessagesOnlyThrowProtocolException() {
    byte[] good =
        ConsensusMessage.encode(new ConsensusMessage.Proposal(3, 1, -1, 1, block(), sig(1)));
    SplittableRandom rnd = new SplittableRandom(5);
    for (int i = 0; i < 20_000; i++) {
      boolean mutate = rnd.nextBoolean();
      byte[] m;
      if (mutate) {
        m = good.clone();
        m[rnd.nextInt(m.length)] ^= (byte) (1 << rnd.nextInt(8));
      } else {
        m = new byte[rnd.nextInt(0, 300)];
        rnd.nextBytes(m);
      }
      try {
        ConsensusMessage.decode(m);
      } catch (ProtocolException expected) {
        // fine
      }
    }
    assertThatThrownBy(() -> ConsensusMessage.decode(Arrays.copyOf(good, good.length - 1)))
        .isInstanceOf(ProtocolException.class);
  }

  @Test
  void safetyStateRoundTripsAndForbidsContradictions() throws ProtocolException {
    byte[] x = new byte[32];
    x[0] = 1;
    SafetyState s =
        new SafetyState(
            5,
            2,
            1,
            block(),
            new SafetyState.Signed(5, 2, x),
            new SafetyState.Signed(5, 2, null),
            new SafetyState.Signed(5, 1, x));
    SafetyState back = SafetyState.decode(s.encode());
    assertThat(back.encode()).isEqualTo(s.encode());
    SafetyState.Signed prevote = back.prevote();
    assertThat(prevote.forbids(5, 2, x)).as("different target, same (h, r)").isTrue();
    assertThat(prevote.forbids(5, 2, null)).as("same nil vote").isFalse();
    assertThat(prevote.forbids(5, 1, null)).as("going back a round").isTrue();
    assertThat(prevote.forbids(4, 9, null)).as("going back a height").isTrue();
    assertThat(prevote.forbids(5, 3, x)).as("later round").isFalse();
    assertThat(SafetyState.initial(7).lockedRound()).isEqualTo(-1);
  }

  @Test
  void walKeepsTheLastStateAndRefusesEarlierCorruption() throws Exception {
    Path f = dir.resolve("consensus.wal");
    ConsensusWal wal = new ConsensusWal(f);
    assertThat(wal.read()).isNull();
    WalSafetyStore store = new WalSafetyStore(wal);
    assertThat(store.load()).isNull();
    SafetyState first =
        new SafetyState(9, 0, -1, null, null, new SafetyState.Signed(9, 0, null), null);
    SafetyState second =
        new SafetyState(9, 1, -1, null, null, new SafetyState.Signed(9, 1, null), null);
    store.save(first);
    store.save(second);
    wal.close();
    assertThat(new WalSafetyStore(new ConsensusWal(f)).load().encode()).isEqualTo(second.encode());

    byte[] bytes = Files.readAllBytes(f);
    // A torn final record (crash mid-append) falls back to the previous state.
    Files.write(f, Arrays.copyOf(bytes, bytes.length - 3));
    assertThat(new ConsensusWal(f).read()).isEqualTo(first.encode());
    byte[] lastFlipped = bytes.clone();
    lastFlipped[lastFlipped.length - 1] ^= 1;
    Files.write(f, lastFlipped);
    assertThat(new ConsensusWal(f).read()).isEqualTo(first.encode());
    // Corruption before the last record is refused.
    byte[] firstFlipped = bytes.clone();
    firstFlipped[10] ^= 1;
    Files.write(f, firstFlipped);
    assertThatThrownBy(() -> new ConsensusWal(f).read()).isInstanceOf(PersistenceException.class);
  }

  @Test
  void walCompactsLongLogs() throws Exception {
    Path f = dir.resolve("big.wal");
    try (ConsensusWal wal = new ConsensusWal(f)) {
      byte[] payload = new byte[4_000];
      for (int i = 0; i < 400; i++) {
        payload[0] = (byte) i;
        wal.write(payload);
      }
      assertThat(Files.size(f)).isLessThan(2L << 20);
      assertThat(wal.read()[0]).isEqualTo((byte) (399 & 0xFF));
    }
  }
}
