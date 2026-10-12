package com.sajin.mizan.chain;

import com.sajin.mizan.crypto.CryptoException;
import com.sajin.mizan.crypto.Signer;
import com.sajin.mizan.engine.Sequencer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Consensus for a one-validator chain: every draft becomes a block at round 0, certified by the
 * validator's own precommit signature (a 1-of-1 quorum). Block timestamps strictly increase.
 */
public final class SingleNodeConsensus extends AbstractConsensus {
  private final Signer key;
  private final LongSupplier clockMillis;

  public SingleNodeConsensus(ValidatorSet validators, Signer key, LongSupplier clockMillis) {
    super(validators);
    if (validators.size() != 1) {
      throw new IllegalArgumentException("single-node consensus needs exactly one validator");
    }
    if (!java.util.Arrays.equals(validators.key(0), key.publicKey())) {
      throw new IllegalArgumentException("key does not belong to validator 0");
    }
    this.key = key;
    this.clockMillis = clockMillis;
  }

  @Override
  public CompletableFuture<CommittedBlock> propose(
      Sequencer.BlockDraft draft, long height, byte[] prevHash, long prevTimestampMillis) {
    long ts = Math.max(prevTimestampMillis + 1, clockMillis.getAsLong());
    Block block =
        new Block(
            new BlockHeader(
                BlockHeader.VERSION,
                validators.chainId(),
                height,
                0,
                prevHash,
                Block.txRoot(draft.commands()),
                draft.stateRoot(),
                0,
                ts),
            draft.commands());
    try {
      byte[] sig =
          key.sign(Votes.signBytes(validators.chainId(), Votes.PRECOMMIT, height, 0, block.hash()));
      return CompletableFuture.completedFuture(
          new CommittedBlock(block, new CommitCert(0, List.of(new CommitCert.Sig(0, sig)))));
    } catch (CryptoException e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
