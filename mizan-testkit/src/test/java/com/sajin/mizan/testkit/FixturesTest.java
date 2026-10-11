package com.sajin.mizan.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.crypto.SignatureVerifier;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class FixturesTest {
  private static final SignatureVerifier VERIFIER = SignatureVerifier.ed25519();

  private static boolean verifies(SignedCommand sc, byte[] publicKey) {
    return VERIFIER.verify(publicKey, CommandCodec.unsignedBytes(sc), sc.signature());
  }

  @Test
  void fixtureKeysAreStableTestVectors() {
    // Stored test vector: catches any accidental change to fixture key derivation.
    assertThat(
            HexFormat.of()
                .formatHex(Arrays.copyOfRange(Fixtures.adminSigner().publicKey(), 12, 44)))
        .isEqualTo(FixtureVectors.ADMIN_PUBLIC_KEY_HEX);
    assertThat(Fixtures.accountSigner(7).publicKey())
        .isEqualTo(Fixtures.accountSigner(7).publicKey());
    assertThat(Fixtures.accountSigner(7).publicKey())
        .isNotEqualTo(Fixtures.accountSigner(8).publicKey());
    assertThat(Fixtures.validatorSigner(1).publicKey())
        .isNotEqualTo(Fixtures.accountSigner(1).publicKey());
  }

  @Test
  void genesisFixtureIsConsistent() {
    GenesisConfig g = Fixtures.genesis(4);
    assertThat(g.chainId()).isEqualTo(Fixtures.CHAIN_ID);
    assertThat(g.adminPubKey()).isEqualTo(Fixtures.adminSigner().publicKey());
    assertThat(g.validators()).hasSize(4);
    assertThat(g.validators().get(3).pubKey()).isEqualTo(Fixtures.validatorSigner(3).publicKey());
    assertThat(g.instrument(Fixtures.INSTRUMENT)).contains(Fixtures.instrument());
    assertThat(g.instrument(Fixtures.ALT_INSTRUMENT)).contains(Fixtures.altInstrument());
    assertThat(Fixtures.genesis().validators()).hasSize(1);
  }

  @Test
  void signedCommandsVerifyAndRoundTrip() throws ProtocolException {
    SignedCommand sc = SignedCommands.limit(5, 3, 1, Side.BUY, 100, 2, TimeInForce.GTC);
    assertThat(verifies(sc, Fixtures.accountSigner(5).publicKey())).isTrue();
    assertThat(CommandCodec.decode(sc.raw()).body()).isEqualTo(sc.body());
    assertThat(sc.chainId()).isEqualTo(Fixtures.CHAIN_ID);
    assertThat(((Place) sc.body()).clientOrderId()).isEqualTo(3);

    SignedCommand reg = SignedCommands.register(5, 1);
    assertThat(((Register) reg.body()).pubKey()).isEqualTo(Fixtures.accountSigner(5).publicKey());
    assertThat(verifies(reg, ((Register) reg.body()).pubKey())).isTrue();

    SignedCommand dep = SignedCommands.deposit(1, 5, Fixtures.QUOTE, 1_000);
    assertThat(dep.accountId()).isEqualTo(Accounts.ADMIN_ACCOUNT);
    assertThat(verifies(dep, Fixtures.adminSigner().publicKey())).isTrue();
  }

  @Test
  void hostileVariantsDoNotVerify() {
    SignedCommand sc = SignedCommands.market(5, 4, 1, Side.SELL, 1, 2);
    byte[] pk = Fixtures.accountSigner(5).publicKey();
    assertThat(verifies(SignedCommands.withBadSignature(sc), pk)).isFalse();
    SignedCommand forged = SignedCommands.signedByWrongKey(sc, 6);
    assertThat(forged.accountId()).isEqualTo(5);
    assertThat(verifies(forged, pk)).isFalse();
  }
}
