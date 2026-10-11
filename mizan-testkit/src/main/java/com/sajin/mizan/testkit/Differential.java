package com.sajin.mizan.testkit;

import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import java.util.List;
import java.util.Objects;

/**
 * Runs the real {@link ExecutionCore} and the naive {@link ReferenceEngine} side by side. After
 * <em>every</em> command it compares the result status and order id, the full event stream, and the
 * full {@link StateDump}, and fails with a detailed {@link AssertionError} at the first difference.
 */
public final class Differential {
  private final ExecutionCore core;
  private final ReferenceEngine reference;
  private final RecordingListener coreEvents = new RecordingListener();
  private final RecordingListener refEvents = new RecordingListener();
  private int step;

  public Differential(GenesisConfig g, SignatureVerifier verifier) {
    this(g, verifier, ReferenceEngine.Bug.NONE);
  }

  public Differential(GenesisConfig g, SignatureVerifier verifier, ReferenceEngine.Bug bug) {
    this.core = ExecutionCore.genesis(g, verifier);
    this.reference = new ReferenceEngine(g, verifier, bug);
    assertSameState("genesis");
  }

  /** The engine under test, for inspection between steps. Do not execute on it directly. */
  public ExecutionCore core() {
    return core;
  }

  /** Executes {@code c} on both engines and compares everything. Returns the engine's result. */
  public ExecResult step(SignedCommand c) {
    step++;
    ExecResult a = core.execute(c, coreEvents);
    ExecResult b = reference.execute(c, refEvents);
    String where = "step " + step + " " + c.body() + " by " + c.accountId() + "/" + c.nonce();
    if (a.status() != b.status() || a.orderId() != b.orderId()) {
      throw new AssertionError(
          where + ": result differs\n  engine:    " + a + "\n  reference: " + b);
    }
    var ea = coreEvents.drain();
    var eb = refEvents.drain();
    if (!ea.equals(eb)) {
      throw new AssertionError(
          where + ": events differ\n  engine:    " + ea + "\n  reference: " + eb);
    }
    assertSameState(where);
    return a;
  }

  public void run(List<SignedCommand> commands) {
    commands.forEach(this::step);
  }

  private void assertSameState(String where) {
    StateDump a = StateDump.of(core);
    StateDump b = reference.dump();
    if (!Objects.equals(a, b)) {
      throw new AssertionError(
          where + ": state differs\n  engine:    " + a + "\n  reference: " + b);
    }
  }
}
