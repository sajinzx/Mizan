package com.sajin.mizan.testkit;

import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.OrderCommand;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Signs test commands for an account's fixture key. Two schemes:
 *
 * <ul>
 *   <li>{@link #ED25519}: real signatures, verified by {@link SignatureVerifier#ed25519()};
 *   <li>{@link #FAST}: a deterministic stand-in where {@code signature = SHA-512(pubKey ||
 *       message)}, verified by {@link #FAST_VERIFIER}. It is <b>not</b> a signature scheme (anyone
 *       can compute it). It lets 100k-try property runs exercise the authentication logic (wrong
 *       key, tampered bytes, replay) without paying Ed25519 cost (ADR 0009).
 * </ul>
 */
public interface CommandSigner {

  SignedCommand sign(int chainId, long account, long nonce, OrderCommand body);

  /** Real Ed25519 with each account's fixture key. */
  CommandSigner ED25519 =
      (chainId, account, nonce, body) ->
          SignedCommands.sign(Fixtures.accountSigner(account), chainId, account, nonce, body);

  /** Fast deterministic stand-in; pair with {@link #FAST_VERIFIER}. */
  CommandSigner FAST =
      (chainId, account, nonce, body) -> {
        byte[] unsigned = CommandCodec.encodeUnsigned(chainId, account, nonce, body);
        try {
          return CommandCodec.assemble(
              unsigned, fastTag(Fixtures.accountSigner(account).publicKey(), unsigned));
        } catch (ProtocolException e) {
          throw new IllegalStateException(e);
        }
      };

  /** Accepts exactly the tags produced by {@link #FAST}. */
  SignatureVerifier FAST_VERIFIER =
      (pk, msg, sig) ->
          pk != null && msg != null && sig != null && Arrays.equals(sig, fastTag(pk, msg));

  /** {@code SHA-512(pubKey || message)}: 64 bytes, the size of an Ed25519 signature. */
  static byte[] fastTag(byte[] pubKey, byte[] message) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-512");
      md.update(pubKey);
      md.update(message);
      return md.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
