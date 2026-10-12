package com.sajin.mizan.chain;

import java.util.Objects;

/** Shared validator arithmetic for consensus engines: {@code n}, {@code f}, quorum, proposer. */
public abstract class AbstractConsensus implements ConsensusEngine {
  protected final ValidatorSet validators;

  protected AbstractConsensus(ValidatorSet validators) {
    this.validators = Objects.requireNonNull(validators, "validators");
  }

  public final int n() {
    return validators.size();
  }

  public final int f() {
    return validators.f();
  }

  public final int quorum() {
    return validators.quorum();
  }

  public final int proposerFor(long height, int round) {
    return validators.proposer(height, round);
  }
}
