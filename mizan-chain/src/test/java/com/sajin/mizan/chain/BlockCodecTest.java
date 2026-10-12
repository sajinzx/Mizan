package com.sajin.mizan.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.crypto.CryptoException;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.SignedCommands;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** M6-T1: canonical block, cert and vote encodings. */
class BlockCodecTest {

  static List<SignedCommand> commands(int n) {
    List<SignedCommand> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(
          SignedCommands.limit(
              2 + i % 3, 10 + i, 1, i % 2 == 0 ? Side.BUY : Side.SELL, 100, 1, TimeInForce.GTC));
    }
    return out;
  }

  static Block block(long height, List<SignedCommand> cmds) {
    byte[] prev = new byte[32];
    prev[0] = 7;
    byte[] state = new byte[32];
    state[31] = 9;
    return new Block(
        new BlockHeader(
            BlockHeader.VERSION,
            Fixtures.CHAIN_ID,
            height,
            0,
            prev,
            Block.txRoot(cmds),
            state,
            0,
            1_700_000_000_000L),
        cmds);
  }

  static CommitCert cert(Block b, int... validators) throws CryptoException {
    List<CommitCert.Sig> sigs = new ArrayList<>();
    byte[] msg = Votes.signBytes(Fixtures.CHAIN_ID, Votes.PRECOMMIT, b.height(), 0, b.hash());
    for (int v : validators) {
      sigs.add(new CommitCert.Sig(v, Fixtures.validatorSigner(v).sign(msg)));
    }
    return new CommitCert(0, sigs);
  }

  @Test
  void committedBlockRoundTrips() throws Exception {
    for (int n : new int[] {0, 1, 7}) {
      Block b = block(5, commands(n));
      CommittedBlock cb = new CommittedBlock(b, cert(b, 0));
      byte[] bytes = cb.encode();
      CommittedBlock back = CommittedBlock.decode(bytes);
      assertThat(back.encode()).isEqualTo(bytes);
      assertThat(back.block().header().sameAs(b.header())).isTrue();
      assertThat(back.block().hash()).isEqualTo(b.hash());
      assertThat(back.block().txRootMatches()).isTrue();
      assertThat(back.height()).isEqualTo(5);
      assertThat(Block.decode(b.encode()).encode()).isEqualTo(b.encode());
    }
    assertThat(block(1, List.of()).header().encode()).hasSize(BlockHeader.BYTES).hasSize(125);
  }

  @Test
  void tamperingIsDetected() throws Exception {
    Block b = block(5, commands(3));
    byte[] bytes = new CommittedBlock(b, cert(b, 0)).encode();
    SplittableRandom rnd = new SplittableRandom(3);
    ValidatorSet vs = new ValidatorSet(Fixtures.genesis());
    for (int i = 0; i < 2_000; i++) {
      byte[] m = bytes.clone();
      m[rnd.nextInt(m.length)] ^= (byte) (1 << rnd.nextInt(8));
      CommittedBlock d;
      try {
        d = CommittedBlock.decode(m);
      } catch (ProtocolException e) {
        continue; // structurally rejected
      }
      boolean intact =
          d.block().txRootMatches()
              && vs.verifies(d.cert(), d.height(), d.block().hash(), SignatureVerifier.ed25519());
      assertThat(intact).as("flip %d undetected", i).isFalse();
    }
  }

  @Test
  void truncationAndTrailingBytesAreRejected() throws Exception {
    Block b = block(2, commands(2));
    byte[] bytes = new CommittedBlock(b, cert(b, 0)).encode();
    for (int len = 0; len < bytes.length; len += 7) {
      byte[] cut = Arrays.copyOf(bytes, len);
      assertThatThrownBy(() -> CommittedBlock.decode(cut)).isInstanceOf(ProtocolException.class);
    }
    assertThatThrownBy(() -> CommittedBlock.decode(Arrays.copyOf(bytes, bytes.length + 1)))
        .isInstanceOf(ProtocolException.class);
  }

  @Test
  void certNeedsAQuorumOfDistinctValidSignatures() throws Exception {
    ValidatorSet four = new ValidatorSet(Fixtures.genesis(4));
    assertThat(four.f()).isEqualTo(1);
    assertThat(four.quorum()).isEqualTo(3);
    assertThat(four.proposer(5, 0)).isEqualTo(1);
    assertThat(four.proposer(5, 3)).isEqualTo(0);
    Block b = block(5, commands(1));
    SignatureVerifier v = SignatureVerifier.ed25519();
    assertThat(four.verifies(cert(b, 0, 1, 2), 5, b.hash(), v)).isTrue();
    assertThat(four.verifies(cert(b, 0, 1), 5, b.hash(), v)).isFalse();
    assertThat(four.verifies(cert(b, 0, 0, 1), 5, b.hash(), v))
        .as("duplicates count once")
        .isFalse();
    assertThat(four.verifies(cert(b, 0, 1, 2), 6, b.hash(), v)).as("wrong height").isFalse();
    CommitCert c = cert(b, 0, 1, 2);
    List<CommitCert.Sig> swapped = new ArrayList<>(c.sigs());
    swapped.set(2, new CommitCert.Sig(3, c.sigs().get(2).signature())); // signed by 2, claims 3
    assertThat(four.verifies(new CommitCert(0, swapped), 5, b.hash(), v)).isFalse();
    assertThat(
            four.verifies(
                new CommitCert(0, List.of(new CommitCert.Sig(9, new byte[64]))), 5, b.hash(), v))
        .isFalse();
  }

  @Test
  void voteSignBytesAreDomainSeparated() {
    byte[] h = new byte[32];
    byte[] pre = Votes.signBytes(1, Votes.PREVOTE, 2, 3, h);
    byte[] com = Votes.signBytes(1, Votes.PRECOMMIT, 2, 3, h);
    assertThat(pre).isNotEqualTo(com).hasSize(7 + 4 + 1 + 8 + 4 + 32);
    assertThat(Votes.signBytes(1, Votes.PREVOTE, 2, 3, null)).isEqualTo(pre); // nil == zero hash
    assertThat(Votes.signBytes(2, Votes.PREVOTE, 2, 3, h)).isNotEqualTo(pre);
    assertThatThrownBy(() -> Votes.signBytes(1, 3, 2, 3, h))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
