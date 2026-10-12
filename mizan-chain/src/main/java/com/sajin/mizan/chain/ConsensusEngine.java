package com.sajin.mizan.chain;

import com.sajin.mizan.engine.Sequencer;
import java.util.concurrent.CompletableFuture;

/**
 * Turns sequencer drafts into committed blocks (M6-T6). Implementations: {@link
 * SingleNodeConsensus} (one validator, self-signed 1-of-1 certificate) and, from M7, the
 * Tendermint-lite {@code RoundBft}.
 */
public interface ConsensusEngine {

  /**
   * Proposes a block for {@code height} built from {@code draft}. The future completes with the
   * committed block and its certificate, or exceptionally if the round cannot commit it.
   *
   * @param prevHash hash of the block at {@code height - 1} (the genesis anchor for height 1)
   * @param prevTimestampMillis timestamp of the previous block (0 at genesis)
   */
  CompletableFuture<CommittedBlock> propose(
      Sequencer.BlockDraft draft, long height, byte[] prevHash, long prevTimestampMillis);

  /** A consensus message from validator {@code from}. Single-node consensus ignores these. */
  default void onMessage(int from, byte[] payload) {}
}
