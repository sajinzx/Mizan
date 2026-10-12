package com.sajin.mizan.bench;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.OrderCommand;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.testkit.Fixtures;
import java.nio.ByteBuffer;
import java.util.SplittableRandom;
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
 * State-root cost per block by account count (M10-T2): {@code accounts} registered and funded
 * accounts; one operation = a block of {@value #BLOCK} deposits to random accounts, then {@code
 * stateRoot()}. {@code version=1} recomputes {@code SimpleMerkle} roots over all state; {@code
 * version=2} updates the sparse Merkle trees with the block's dirty keys only. Signatures are off
 * (benchmark-only flag); deposits change one ledger leaf each plus the admin's nonce.
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class StateRootBench {
  static final int BLOCK = 100;

  @Param({"100", "1000", "10000", "100000"})
  public int accounts;

  @Param({"1", "2"})
  public int version;

  private ExecutionCore core;
  private long adminNonce;
  private SplittableRandom rnd;

  @Setup
  public void setup() {
    var g = version == 2 ? Fixtures.genesisV2() : Fixtures.genesis();
    core = ExecutionCore.genesis(g, SignatureVerifier.ed25519(), false);
    byte[] pk = Fixtures.accountSigner(2).publicKey(); // any well-formed key: not verified
    for (long a = 2; a < 2 + accounts; a++) {
      core.execute(unsigned(a, 1, new Register(pk)), ExecutionCore.SILENT);
      core.execute(
          unsigned(Accounts.ADMIN_ACCOUNT, ++adminNonce, new Deposit(a, Fixtures.QUOTE, 1_000)),
          ExecutionCore.SILENT);
    }
    core.stateRoot();
    rnd = new SplittableRandom(1);
  }

  static SignedCommand unsigned(long account, long nonce, OrderCommand body) {
    try {
      return CommandCodec.assemble(
          CommandCodec.encodeUnsigned(Fixtures.CHAIN_ID, account, nonce, body),
          ByteBuffer.allocate(CommandCodec.SIGNATURE_BYTES).array());
    } catch (ProtocolException e) {
      throw new IllegalStateException(e);
    }
  }

  @Benchmark
  @BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
  public byte[] blockThenRoot() {
    for (int i = 0; i < BLOCK; i++) {
      long a = 2L + rnd.nextInt(accounts);
      core.execute(
          unsigned(Accounts.ADMIN_ACCOUNT, ++adminNonce, new Deposit(a, Fixtures.QUOTE, 1)),
          ExecutionCore.SILENT);
    }
    return core.stateRoot();
  }
}
