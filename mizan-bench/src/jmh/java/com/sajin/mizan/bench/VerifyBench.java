package com.sajin.mizan.bench;

import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.CachingVerifier;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.testkit.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Signature verification on its own (M10-T4), over the {@link BenchStream} commands (real Ed25519
 * signatures by ten trader keys):
 *
 * <ul>
 *   <li>{@code plain}: one verification on the calling thread, no cache;
 *   <li>{@code cachedHit}: {@link CachingVerifier} when the gateway pre-check already verified the
 *       command (the engine's path, ADR 0011): a SHA-256 and a set lookup;
 *   <li>{@code batchVirtualThreads}: {@value #BATCH} verifications spread over virtual threads, as
 *       the gateway's per-connection pre-checks are; reported per signature.
 * </ul>
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class VerifyBench {
  static final int BATCH = 256;

  private SignedCommand[] cmds;
  private byte[][] unsigned;
  private byte[][] keys;
  private SignatureVerifier plain;
  private CachingVerifier cache;
  private ExecutorService pool;
  private int next;

  @Setup
  public void setup() {
    BenchStream s = BenchStream.build(4_096, 42, SignatureVerifier.ed25519(), false);
    cmds = s.commands;
    unsigned = new byte[cmds.length][];
    keys = new byte[cmds.length][];
    for (int i = 0; i < cmds.length; i++) {
      unsigned[i] = CommandCodec.unsignedBytes(cmds[i]);
      keys[i] = Fixtures.accountSigner(cmds[i].accountId()).publicKey();
    }
    plain = SignatureVerifier.ed25519();
    cache = new CachingVerifier(SignatureVerifier.ed25519());
    for (int i = 0; i < cmds.length; i++) {
      cache.verify(keys[i], unsigned[i], cmds[i].signature()); // warm: every lookup is a hit
    }
    pool = Executors.newVirtualThreadPerTaskExecutor();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    pool.shutdownNow();
  }

  private int idx() {
    int i = next;
    next = (next + 1) % cmds.length;
    return i;
  }

  @Benchmark
  @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
  public boolean plain() {
    int i = idx();
    return plain.verify(keys[i], unsigned[i], cmds[i].signature());
  }

  @Benchmark
  @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
  public boolean cachedHit() {
    int i = idx();
    return cache.verify(keys[i], unsigned[i], cmds[i].signature());
  }

  @Benchmark
  @BenchmarkMode(Mode.Throughput)
  @OperationsPerInvocation(BATCH)
  public int batchVirtualThreads() throws Exception {
    List<Future<Boolean>> fs = new ArrayList<>(BATCH);
    for (int k = 0; k < BATCH; k++) {
      int i = idx();
      fs.add(pool.submit(() -> plain.verify(keys[i], unsigned[i], cmds[i].signature())));
    }
    int ok = 0;
    for (Future<Boolean> f : fs) {
      ok += f.get() ? 1 : 0;
    }
    return ok;
  }
}
