package com.sajin.mizan.chain;

import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongBinaryOperator;

/**
 * Block validity (§6.8 table, M7-T6). Never mutates the committed core: commands are replayed on a
 * copy, and the copy and results are returned so an accepted block can be adopted without a second
 * replay.
 *
 * <table>
 *   <caption>checks by mode</caption>
 *   <tr><th>check</th><th>live voting</th><th>sync / replay</th></tr>
 *   <tr><td>version, chainId, height, prevHash, proposer, txRoot, size</td><td>✓</td><td>✓</td></tr>
 *   <tr><td>replay: no AUTH_FAILED (defect 1), root equals header</td><td>✓</td><td>✓</td></tr>
 *   <tr><td>timestamp &gt; previous</td><td>✓</td><td>✓</td></tr>
 *   <tr><td>|timestamp − now| ≤ 5 s</td><td>✓</td><td>✗ (defect 2)</td></tr>
 *   <tr><td>commit certificate quorum</td><td>—</td><td>✓</td></tr>
 * </table>
 */
public final class BlockValidator {
  public static final long MAX_CLOCK_SKEW_MS = 5_000;

  /** Where the chain is: the block must extend this. The hash array is not copied. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Context(
      ValidatorSet validators,
      long height,
      byte[] prevHash,
      long prevTimestampMillis,
      LongBinaryOperator proposerFor,
      int maxCommands) {}

  /** Outcome: either the replayed state, or the reason the block is invalid. */
  public sealed interface Result {
    boolean ok();
  }

  /** The block is valid; {@code next} is the committed core after it. */
  public record Valid(ExecutionCore next, List<ExecResult> results) implements Result {
    @Override
    public boolean ok() {
      return true;
    }
  }

  public record Invalid(String reason) implements Result {
    @Override
    public boolean ok() {
      return false;
    }
  }

  /** How a block is being validated. */
  public enum Mode {
    LIVE,
    SYNC
  }

  private BlockValidator() {}

  /**
   * Validates {@code b} on top of {@code committed}.
   *
   * @param cert required in {@link Mode#SYNC}; ignored in {@link Mode#LIVE}
   */
  public static Result validate(
      Block b,
      CommitCert cert,
      ExecutionCore committed,
      Context ctx,
      Mode mode,
      long nowMillis,
      SignatureVerifier verifier) {
    return validate(b, cert, committed, ctx, mode, nowMillis, verifier, ExecutionCore.SILENT);
  }

  /** As above, delivering the replay's engine events to {@code events}. */
  public static Result validate(
      Block b,
      CommitCert cert,
      ExecutionCore committed,
      Context ctx,
      Mode mode,
      long nowMillis,
      SignatureVerifier verifier,
      EngineListener events) {
    BlockHeader h = b.header();
    ValidatorSet vs = ctx.validators();
    if (h.version() != BlockHeader.VERSION || h.chainId() != vs.chainId()) {
      return new Invalid("wrong version or chain");
    }
    if (h.height() != ctx.height()) {
      return new Invalid("height " + h.height() + " != " + ctx.height());
    }
    if (!Arrays.equals(h.prevHash(), ctx.prevHash())) {
      return new Invalid("prevHash does not extend the head");
    }
    if (h.round() < 0
        || h.proposer() != (int) ctx.proposerFor().applyAsLong(h.height(), h.round())) {
      return new Invalid("wrong proposer for (height, round)");
    }
    if (b.commands().size() > ctx.maxCommands()) {
      return new Invalid("too many commands");
    }
    if (!b.txRootMatches()) {
      return new Invalid("txRoot mismatch");
    }
    if (h.timestampMillis() <= ctx.prevTimestampMillis()) {
      return new Invalid("timestamp not after previous block");
    }
    if (mode == Mode.LIVE && Math.abs(h.timestampMillis() - nowMillis) > MAX_CLOCK_SKEW_MS) {
      return new Invalid("timestamp too far from local clock");
    }
    if (mode == Mode.SYNC && (cert == null || !vs.verifies(cert, h.height(), b.hash(), verifier))) {
      return new Invalid("commit certificate lacks a quorum");
    }
    ExecutionCore next = committed.copy();
    List<ExecResult> results = new ArrayList<>(b.commands().size());
    for (SignedCommand c : b.commands()) {
      ExecResult r = next.execute(c, events);
      if (r.status() == ExecResult.Status.AUTH_FAILED) {
        return new Invalid("contains an unauthenticated command (" + r.reason() + ")"); // defect 1
      }
      results.add(r);
    }
    if (!Arrays.equals(next.stateRoot(), h.stateRoot())) {
      return new Invalid("state root mismatch after replay");
    }
    return new Valid(next, results);
  }
}
