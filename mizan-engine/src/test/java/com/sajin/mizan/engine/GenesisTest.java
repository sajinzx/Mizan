package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.testkit.Fixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M2-T6: genesis bootstrap. */
class GenesisTest {

  @Test
  void genesisCreatesAdminFeeAccountAndEmptyBooks() {
    GenesisConfig g = Fixtures.genesis();
    ExecutionCore core = ExecutionCore.genesis(g, SignatureVerifier.ed25519());

    assertThat(core.isRegistered(Accounts.ADMIN_ACCOUNT)).isTrue();
    assertThat(core.lastNonce(Accounts.ADMIN_ACCOUNT)).isZero();
    assertThat(core.isRegistered(Accounts.FEE_ACCOUNT)).isFalse(); // exists implicitly, no key
    List<byte[]> keys = new ArrayList<>();
    core.forEachAccount((a, n, pk) -> keys.add(pk));
    assertThat(keys).singleElement().isEqualTo(g.adminPubKey());

    assertThat(core.instrumentIds()).containsExactly(Fixtures.INSTRUMENT, Fixtures.ALT_INSTRUMENT);
    for (int i : core.instrumentIds()) {
      assertThat(core.restingOrders(i, Side.BUY)).isEmpty();
      assertThat(core.restingOrders(i, Side.SELL)).isEmpty();
    }
    assertThat(core.nextOrderId()).isEqualTo(1);
    assertThat(core.nextTradeSeq()).isEqualTo(1);
    assertThat(core.ledgerTotal(Fixtures.QUOTE)).isZero();
  }

  @Test
  void genesisIsDeterministic() {
    byte[] a = ExecutionCore.genesis(Fixtures.genesis(), SignatureVerifier.ed25519()).stateRoot();
    byte[] b = ExecutionCore.genesis(Fixtures.genesis(), SignatureVerifier.ed25519()).stateRoot();
    assertThat(a).isEqualTo(b);
  }

  @Test
  void stateRootVersion2IsNotSupportedYet() {
    GenesisConfig g = Fixtures.genesis();
    GenesisConfig v2 =
        new GenesisConfig(
            g.chainId(),
            2,
            g.adminPubKey(),
            g.assets(),
            g.instruments(),
            g.validators(),
            g.block(),
            g.consensus());
    assertThatThrownBy(() -> ExecutionCore.genesis(v2, SignatureVerifier.ed25519()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
