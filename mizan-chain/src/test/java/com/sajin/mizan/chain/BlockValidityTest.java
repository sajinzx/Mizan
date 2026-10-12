package com.sajin.mizan.chain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.SignedCommands;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M7-T6: block validity (§6.8), including regressions for defects 1 and 2. */
class BlockValidityTest {
  static final GenesisConfig G = Fixtures.genesis(4);
  static final ValidatorSet VS = new ValidatorSet(G);
  static final SignatureVerifier V = SignatureVerifier.ed25519();
  static final long NOW = 1_700_000_000_000L;

  final ExecutionCore committed = ExecutionCore.genesis(G, V);
  final byte[] anchor = GenesisAnchor.hash(G);

  BlockValidator.Context ctx() {
    return new BlockValidator.Context(VS, 1, anchor, 0, (h, r) -> VS.proposer(h, (int) r), 500);
  }

  /** A block of {@code cmds} whose header root is whatever replaying them gives. */
  Block block(List<SignedCommand> cmds, long ts) {
    ExecutionCore c = committed.copy();
    cmds.forEach(x -> c.execute(x, ExecutionCore.SILENT));
    return new Block(
        new BlockHeader(
            BlockHeader.VERSION,
            G.chainId(),
            1,
            0,
            anchor,
            Block.txRoot(cmds),
            c.stateRoot(),
            VS.proposer(1, 0),
            ts),
        cmds);
  }

  static List<SignedCommand> good() {
    List<SignedCommand> l = new ArrayList<>();
    l.add(SignedCommands.register(2, 1));
    l.add(SignedCommands.deposit(1, 2, Fixtures.QUOTE, 500));
    return l;
  }

  CommitCert cert(Block b, int... validators) throws Exception {
    List<CommitCert.Sig> sigs = new ArrayList<>();
    for (int v : validators) {
      sigs.add(
          new CommitCert.Sig(
              v,
              Fixtures.validatorSigner(v)
                  .sign(Votes.signBytes(G.chainId(), Votes.PRECOMMIT, 1, 0, b.hash()))));
    }
    return new CommitCert(0, sigs);
  }

  @Test
  void validBlockReplaysOnACopy() {
    byte[] before = committed.stateRoot();
    var r =
        BlockValidator.validate(
            block(good(), NOW), null, committed, ctx(), BlockValidator.Mode.LIVE, NOW, V);
    assertThat(r).isInstanceOf(BlockValidator.Valid.class);
    assertThat(((BlockValidator.Valid) r).results()).hasSize(2);
    assertThat(committed.stateRoot()).as("committed core untouched").isEqualTo(before);
  }

  /** Defect 1: a block containing a command that fails authentication is invalid as a whole. */
  @Test
  void authFailedInBlockIsInvalid() {
    List<SignedCommand> cmds = good();
    cmds.add(SignedCommands.withBadSignature(SignedCommands.deposit(2, 2, Fixtures.QUOTE, 5)));
    var r =
        BlockValidator.validate(
            block(cmds, NOW), null, committed, ctx(), BlockValidator.Mode.LIVE, NOW, V);
    assertThat(r).isInstanceOf(BlockValidator.Invalid.class);
    assertThat(((BlockValidator.Invalid) r).reason()).contains("unauthenticated");
  }

  @Test
  void businessRejectsAreValidNoOps() {
    List<SignedCommand> cmds = good();
    cmds.add(
        SignedCommands.sign(
            2, 2, new Deposit(2, Fixtures.QUOTE, 5))); // non-admin deposit: AUTH_FAILED
    assertThat(
            BlockValidator.validate(
                    block(cmds, NOW), null, committed, ctx(), BlockValidator.Mode.LIVE, NOW, V)
                .ok())
        .isFalse();
    List<SignedCommand> rejected = good();
    rejected.add(SignedCommands.deposit(2, 2, 99, 5)); // unknown asset: REJECTED, burns a nonce
    assertThat(
            BlockValidator.validate(
                    block(rejected, NOW), null, committed, ctx(), BlockValidator.Mode.LIVE, NOW, V)
                .ok())
        .isTrue();
  }

  /**
   * Defect 2: an old block is invalid for <em>live</em> voting (clock skew) but valid when syncing
   * or replaying with a quorum certificate.
   */
  @Test
  void oldBlockValidDuringSync() throws Exception {
    long anHourAgo = NOW - 3_600_000;
    Block old = block(good(), anHourAgo);
    var live =
        BlockValidator.validate(old, null, committed, ctx(), BlockValidator.Mode.LIVE, NOW, V);
    assertThat(live.ok()).isFalse();
    assertThat(((BlockValidator.Invalid) live).reason()).contains("clock");
    var sync =
        BlockValidator.validate(
            old, cert(old, 0, 1, 2), committed, ctx(), BlockValidator.Mode.SYNC, NOW, V);
    assertThat(sync.ok()).isTrue();
  }

  @Test
  void syncNeedsAQuorumCertificate() throws Exception {
    Block b = block(good(), NOW);
    assertThat(
            BlockValidator.validate(
                    b, cert(b, 0, 1), committed, ctx(), BlockValidator.Mode.SYNC, NOW, V)
                .ok())
        .isFalse();
    assertThat(
            BlockValidator.validate(b, null, committed, ctx(), BlockValidator.Mode.SYNC, NOW, V)
                .ok())
        .isFalse();
  }

  @Test
  void structuralChecks() {
    Block b = block(good(), NOW);
    BlockHeader h = b.header();
    List<Block> bad =
        List.of(
            new Block(
                new BlockHeader(
                    h.version(),
                    h.chainId() + 1,
                    1,
                    0,
                    anchor,
                    h.txRoot(),
                    h.stateRoot(),
                    h.proposer(),
                    NOW),
                b.commands()),
            new Block(
                new BlockHeader(
                    h.version(),
                    h.chainId(),
                    2,
                    0,
                    anchor,
                    h.txRoot(),
                    h.stateRoot(),
                    h.proposer(),
                    NOW),
                b.commands()),
            new Block(
                new BlockHeader(
                    h.version(),
                    h.chainId(),
                    1,
                    0,
                    new byte[32],
                    h.txRoot(),
                    h.stateRoot(),
                    h.proposer(),
                    NOW),
                b.commands()),
            new Block(
                new BlockHeader(
                    h.version(), h.chainId(), 1, 0, anchor, h.txRoot(), h.stateRoot(), 3, NOW),
                b.commands()),
            new Block(
                new BlockHeader(
                    h.version(),
                    h.chainId(),
                    1,
                    0,
                    anchor,
                    new byte[32],
                    h.stateRoot(),
                    h.proposer(),
                    NOW),
                b.commands()),
            new Block(
                new BlockHeader(
                    h.version(),
                    h.chainId(),
                    1,
                    0,
                    anchor,
                    h.txRoot(),
                    new byte[32],
                    h.proposer(),
                    NOW),
                b.commands()),
            new Block(
                new BlockHeader(
                    h.version(),
                    h.chainId(),
                    1,
                    0,
                    anchor,
                    h.txRoot(),
                    h.stateRoot(),
                    h.proposer(),
                    0),
                b.commands()));
    for (Block x : bad) {
      assertThat(
              BlockValidator.validate(x, null, committed, ctx(), BlockValidator.Mode.LIVE, NOW, V)
                  .ok())
          .as("%s", x)
          .isFalse();
    }
    assertThat(SignedCommands.register(9, 1).body()).isInstanceOf(Register.class);
  }
}
