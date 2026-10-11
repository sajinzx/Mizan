package com.sajin.mizan.bench;

import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Single-threaded {@code ExecutionCore.execute} over the {@link BenchStream} workload (32,768
 * pre-signed commands, replayed from a copy of the initial state when exhausted).
 *
 * <ul>
 *   <li>{@code signatures=off}: signature verification disabled (benchmark-only engine flag);
 *   <li>{@code signatures=ed25519}: every command verified with the JDK Ed25519 provider, no cache.
 * </ul>
 *
 * <p>The state root is not computed per command; it is computed per block and benchmarked
 * separately later (§10).
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ExecuteBench {
  static final int STREAM = 1 << 15;

  @Param({"off", "ed25519"})
  public String signatures;

  private ExecutionCore initial;
  private ExecutionCore core;
  private SignedCommand[] commands;
  private int next;

  @Setup
  public void setup() {
    boolean verify = signatures.equals("ed25519");
    BenchStream s = BenchStream.build(STREAM, 42, SignatureVerifier.ed25519(), verify);
    initial = s.initial;
    commands = s.commands;
    core = initial.copy();
    next = 0;
  }

  private ExecResult step() {
    if (next == commands.length) {
      core = initial.copy();
      next = 0;
    }
    return core.execute(commands[next++], ExecutionCore.SILENT);
  }

  @Benchmark
  @BenchmarkMode(Mode.Throughput)
  public ExecResult throughput() {
    return step();
  }

  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  public ExecResult latency() {
    return step();
  }
}
