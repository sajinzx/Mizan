package com.sajin.mizan.node;

import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.ResultListener;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.net.TcpGateway;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway signature pre-check (M4-T2, defect 7). It verifies on the gateway's virtual threads, in
 * parallel, against a directory of registered keys, and drops junk before it reaches the
 * single-threaded sequencer. It shares a {@code CachingVerifier} with the engine, so the engine's
 * own verification of the same command is a cache hit.
 *
 * <p>The key directory is learned from accepted {@code REGISTER} results. It is only an
 * optimisation: the engine re-verifies everything against its own state.
 */
final class SignaturePreCheck implements TcpGateway.PreCheck, ResultListener, FinalityListener {
  private final SignatureVerifier verifier;
  private final ConcurrentHashMap<Long, byte[]> keys = new ConcurrentHashMap<>();

  SignaturePreCheck(SignatureVerifier verifier) {
    this.verifier = verifier;
  }

  void learn(long account, byte[] publicKey) {
    keys.put(account, publicKey.clone());
  }

  @Override
  public boolean ok(SignedCommand c) {
    byte[] key = c.body() instanceof Register r ? r.pubKey() : keys.get(c.accountId());
    return key != null && verifier.verify(key, CommandCodec.unsignedBytes(c), c.signature());
  }

  /** Learns keys from committed registrations (rotation mode, where others execute them). */
  @Override
  public void onFinal(long height, List<SignedCommand> cmds, List<ExecResult> results) {
    for (int i = 0; i < cmds.size(); i++) {
      onResult(cmds.get(i), results.get(i));
    }
  }

  @Override
  public void onResult(SignedCommand c, ExecResult r) {
    if (c.body() instanceof Register reg && r.status() == ExecResult.Status.ACCEPTED) {
      learn(c.accountId(), reg.pubKey());
    }
  }
}
