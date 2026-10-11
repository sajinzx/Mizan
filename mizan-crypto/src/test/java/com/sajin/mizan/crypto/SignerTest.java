package com.sajin.mizan.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class SignerTest {
  private static final HexFormat HEX = HexFormat.of();
  private static final byte[] X509_ED25519_PREFIX = HEX.parseHex("302a300506032b6570032100");
  private final SignatureVerifier verifier = SignatureVerifier.ed25519();

  /** Mirrors the §5.3 envelope header: version, tag, chainId, account, nonce. */
  private static byte[] signBytes(int chainId, long account, long nonce) {
    return ByteBuffer.allocate(22)
        .put((byte) 1)
        .put((byte) 4)
        .putInt(chainId)
        .putLong(account)
        .putLong(nonce)
        .array();
  }

  @Test
  void rfc8032TestVector1() throws CryptoException {
    Signer s =
        Signer.fromSeed(
            HEX.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"));
    byte[] pk = s.publicKey();
    assertThat(pk).hasSize(44);
    assertThat(Arrays.copyOf(pk, 12)).isEqualTo(X509_ED25519_PREFIX);
    assertThat(HEX.formatHex(Arrays.copyOfRange(pk, 12, 44)))
        .isEqualTo("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    byte[] sig = s.sign(new byte[0]);
    assertThat(HEX.formatHex(sig))
        .isEqualTo(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
    assertThat(verifier.verify(pk, new byte[0], sig)).isTrue();
  }

  @Test
  void rfc8032TestVector2() throws CryptoException {
    Signer s =
        Signer.fromSeed(
            HEX.parseHex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"));
    assertThat(HEX.formatHex(Arrays.copyOfRange(s.publicKey(), 12, 44)))
        .isEqualTo("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
    assertThat(HEX.formatHex(s.sign(new byte[] {0x72})))
        .isEqualTo(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00");
  }

  @Test
  void signVerifyAndTamper() throws CryptoException {
    Signer s = Signer.generate();
    byte[] msg = signBytes(4242, 7, 1);
    byte[] sig = s.sign(msg);
    assertThat(sig).hasSize(64);
    assertThat(verifier.verify(s.publicKey(), msg, sig)).isTrue();

    byte[] tamperedMsg = msg.clone();
    tamperedMsg[msg.length - 1] ^= 1;
    assertThat(verifier.verify(s.publicKey(), tamperedMsg, sig)).isFalse();

    byte[] tamperedSig = sig.clone();
    tamperedSig[0] ^= 1;
    assertThat(verifier.verify(s.publicKey(), msg, tamperedSig)).isFalse();
  }

  @Test
  void wrongKeyFails() throws CryptoException {
    Signer a = Signer.generate();
    Signer b = Signer.generate();
    byte[] msg = signBytes(4242, 7, 1);
    assertThat(verifier.verify(b.publicKey(), msg, a.sign(msg))).isFalse();
  }

  @Test
  void wrongChainIdFails() throws CryptoException {
    Signer s = Signer.generate();
    byte[] sig = s.sign(signBytes(4242, 7, 1));
    assertThat(verifier.verify(s.publicKey(), signBytes(4243, 7, 1), sig)).isFalse();
    assertThat(verifier.verify(s.publicKey(), signBytes(4242, 7, 2), sig)).isFalse();
  }

  @Test
  void malformedInputsDoNotVerifyAndDoNotThrow() throws CryptoException {
    Signer s = Signer.generate();
    byte[] msg = {1, 2, 3};
    byte[] sig = s.sign(msg);
    assertThat(verifier.verify(new byte[44], msg, sig)).isFalse();
    assertThat(verifier.verify(Arrays.copyOf(s.publicKey(), 43), msg, sig)).isFalse();
    assertThat(verifier.verify(s.publicKey(), msg, Arrays.copyOf(sig, 63))).isFalse();
    assertThat(verifier.verify(s.publicKey(), msg, new byte[64])).isFalse();
    assertThat(verifier.verify(null, msg, sig)).isFalse();
    assertThat(verifier.verify(s.publicKey(), null, sig)).isFalse();
    assertThat(verifier.verify(s.publicKey(), msg, null)).isFalse();
  }

  @Test
  void seededKeysAreDeterministicAndDistinct() throws CryptoException {
    byte[] seed = new byte[32];
    seed[0] = 1;
    assertThat(Signer.fromSeed(seed).publicKey()).isEqualTo(Signer.fromSeed(seed).publicKey());
    byte[] other = seed.clone();
    other[0] = 2;
    assertThat(Signer.fromSeed(other).publicKey()).isNotEqualTo(Signer.fromSeed(seed).publicKey());
    assertThatThrownBy(() -> Signer.fromSeed(new byte[31]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void publicKeyIsDefensivelyCopied() throws CryptoException {
    Signer s = Signer.generate();
    byte[] pk = s.publicKey();
    pk[20] ^= 1;
    assertThat(s.publicKey()).isNotEqualTo(pk);
  }
}
