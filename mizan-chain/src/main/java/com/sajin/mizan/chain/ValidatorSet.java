package com.sajin.mizan.chain;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.crypto.SignatureVerifier;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The static validator set from genesis: {@code n = 3f + 1} tolerates {@code f} faults, and a
 * quorum is {@code 2f + 1}.
 */
public final class ValidatorSet {
  private final int chainId;
  private final List<byte[]> keys;

  public ValidatorSet(GenesisConfig g) {
    this.chainId = g.chainId();
    List<byte[]> k = new ArrayList<>();
    g.validators().forEach(v -> k.add(v.pubKey().clone()));
    if (k.isEmpty()) {
      throw new IllegalArgumentException("genesis has no validators");
    }
    this.keys = List.copyOf(k);
  }

  public int size() {
    return keys.size();
  }

  /** Maximum tolerated faulty validators. */
  public int f() {
    return (keys.size() - 1) / 3;
  }

  public int quorum() {
    return 2 * f() + 1;
  }

  public int chainId() {
    return chainId;
  }

  public byte[] key(int index) {
    return keys.get(index).clone();
  }

  /** Proposer for {@code (height, round)}: {@code (height + round) mod n} (§6.8). */
  public int proposer(long height, int round) {
    return (int) Math.floorMod(height + round, (long) keys.size());
  }

  /**
   * Whether {@code cert} holds at least a quorum of distinct, valid precommit signatures for {@code
   * blockHash} at {@code height} and the cert's round.
   */
  public boolean verifies(
      CommitCert cert, long height, byte[] blockHash, SignatureVerifier verifier) {
    byte[] msg = Votes.signBytes(chainId, Votes.PRECOMMIT, height, cert.round(), blockHash);
    BitSet seen = new BitSet(keys.size());
    int valid = 0;
    for (CommitCert.Sig s : cert.sigs()) {
      int i = s.validatorIndex();
      if (i < 0 || i >= keys.size() || seen.get(i)) {
        continue;
      }
      if (verifier.verify(keys.get(i), msg, s.signature())) {
        seen.set(i);
        valid++;
      }
    }
    return valid >= quorum();
  }
}
