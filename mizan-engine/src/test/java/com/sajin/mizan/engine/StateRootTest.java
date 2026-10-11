package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.testkit.Fixtures;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** M2-T7: state root v1. */
class StateRootTest {

  /**
   * Genesis root computed independently with Python hashlib from the §5.6 definition: empty ledger
   * and book (32 zero bytes), one meta leaf for the admin (nonce 0, SHA-256 of its X.509 key),
   * counters 1 and 1.
   */
  @Test
  void genesisRootKnownAnswer() {
    ExecutionCore core = ExecutionCore.genesis(Fixtures.genesis(), SignatureVerifier.ed25519());
    assertThat(HexFormat.of().formatHex(core.stateRoot()))
        .isEqualTo("ebfb59a68b8ae2f5ade873f767bda9cf108c0a5434fa6a5eaf235dff7a056c52");
  }

  @Test
  void everyStateComponentAffectsTheRoot() {
    TestCore t = TestCore.funded();
    byte[] r0 = t.core.stateRoot();
    t.gtc(3, Side.SELL, 100, 5); // book + held + counters
    byte[] r1 = t.core.stateRoot();
    assertThat(r1).isNotEqualTo(r0);
    t.limit(2, Side.BUY, 90, 1, TimeInForce.FOK); // rejected: only the nonce changes
    byte[] r2 = t.core.stateRoot();
    assertThat(r2).isNotEqualTo(r1);
    t.gtc(2, Side.BUY, 100, 2); // trade: balances, book remaining, trade seq
    assertThat(t.core.stateRoot()).isNotEqualTo(r2);
  }

  @Test
  void queueOrderIsPinnedByOrderIds() {
    // Same resting quantities at the same price, but different owners first in the queue.
    TestCore a = TestCore.funded();
    a.gtc(3, Side.SELL, 100, 5);
    a.gtc(4, Side.SELL, 100, 5);
    TestCore b = TestCore.funded();
    b.gtc(4, Side.SELL, 100, 5);
    b.gtc(3, Side.SELL, 100, 5);
    assertThat(a.core.stateRoot()).isNotEqualTo(b.core.stateRoot());
  }

  @Test
  void identicalHistoriesGiveIdenticalRoots() {
    TestCore a = TestCore.funded();
    TestCore b = TestCore.funded();
    for (TestCore t : new TestCore[] {a, b}) {
      t.gtc(3, Side.SELL, 100, 5);
      t.gtc(2, Side.BUY, 101, 7);
      t.market(4, Side.SELL, 90, 1);
    }
    assertThat(a.core.stateRoot()).isEqualTo(b.core.stateRoot());
  }
}
