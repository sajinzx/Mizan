package com.sajin.mizan.testkit;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Cancel;
import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.OrderCommand;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.crypto.CryptoException;
import com.sajin.mizan.crypto.Signer;

/**
 * Builds correctly signed command envelopes for tests. Checked exceptions are rethrown unchecked:
 * fixtures are always well-formed, so a failure here is a test bug.
 */
public final class SignedCommands {
  private SignedCommands() {}

  /** Signs {@code body} as {@code account} with an explicit key and chain id. */
  public static SignedCommand sign(
      Signer signer, int chainId, long account, long nonce, OrderCommand body) {
    byte[] unsigned = CommandCodec.encodeUnsigned(chainId, account, nonce, body);
    try {
      return CommandCodec.assemble(unsigned, signer.sign(unsigned));
    } catch (CryptoException | ProtocolException e) {
      throw new IllegalStateException("fixture signing failed", e);
    }
  }

  /** Signs with the fixture key of {@code account} on {@link Fixtures#CHAIN_ID}. */
  public static SignedCommand sign(long account, long nonce, OrderCommand body) {
    return sign(Fixtures.accountSigner(account), Fixtures.CHAIN_ID, account, nonce, body);
  }

  /** Registers {@code account} with its own fixture key. */
  public static SignedCommand register(long account, long nonce) {
    return sign(account, nonce, new Register(Fixtures.accountSigner(account).publicKey()));
  }

  /** Admin deposit of {@code amount} of {@code asset} into {@code target}. */
  public static SignedCommand deposit(long nonce, long target, int asset, long amount) {
    return sign(Accounts.ADMIN_ACCOUNT, nonce, new Deposit(target, asset, amount));
  }

  public static SignedCommand limit(
      long account, long nonce, int instrument, Side side, long price, long qty, TimeInForce tif) {
    return sign(
        account, nonce, new Place(nonce, instrument, side, OrderType.LIMIT, tif, price, qty));
  }

  /** Market order; {@code collar} is the worst acceptable price. Market orders must be IOC. */
  public static SignedCommand market(
      long account, long nonce, int instrument, Side side, long collar, long qty) {
    return sign(
        account,
        nonce,
        new Place(nonce, instrument, side, OrderType.MARKET, TimeInForce.IOC, collar, qty));
  }

  public static SignedCommand cancel(long account, long nonce, long orderId, int instrument) {
    return sign(account, nonce, new Cancel(orderId, instrument));
  }

  /** The same envelope with one signature bit flipped: decodes fine, never verifies. */
  public static SignedCommand withBadSignature(SignedCommand sc) {
    byte[] raw = sc.raw().clone();
    raw[raw.length - 1] ^= 1;
    try {
      return CommandCodec.decode(raw);
    } catch (ProtocolException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Re-signs {@code sc}'s body with another account's key: a well-formed impersonation attempt. */
  public static SignedCommand signedByWrongKey(SignedCommand sc, long impostor) {
    return sign(
        Fixtures.accountSigner(impostor), sc.chainId(), sc.accountId(), sc.nonce(), sc.body());
  }
}
