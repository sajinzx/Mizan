package com.sajin.mizan.engine;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Cancel;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.OrderCommand;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import com.sajin.mizan.testkit.RecordingListener;

/**
 * Scenario harness: a genesis core with traders 2..6 registered, plus helpers that sign with
 * consecutive nonces. Uses the fast test signer unless built with Ed25519.
 */
final class TestCore {
  static final int I = Fixtures.INSTRUMENT;
  static final int BASE = Fixtures.BASE;
  static final int QUOTE = Fixtures.QUOTE;

  final ExecutionCore core;
  final RecordingListener events = new RecordingListener();
  private final CommandSigner signer;
  private final long[] nonces = new long[16];

  private TestCore(CommandSigner signer, ExecutionCore core) {
    this.signer = signer;
    this.core = core;
  }

  /** Traders 2..6 registered, each funded with {@code quote} QUOTE and {@code base} BASE. */
  static TestCore funded(long quote, long base) {
    TestCore t =
        new TestCore(
            CommandSigner.FAST,
            ExecutionCore.genesis(Fixtures.genesis(), CommandSigner.FAST_VERIFIER));
    for (long a = 2; a <= 6; a++) {
      t.ok(t.cmd(a, new Register(Fixtures.accountSigner(a).publicKey())));
      if (quote > 0) {
        t.ok(t.cmd(Accounts.ADMIN_ACCOUNT, new Deposit(a, QUOTE, quote)));
      }
      if (base > 0) {
        t.ok(t.cmd(Accounts.ADMIN_ACCOUNT, new Deposit(a, BASE, base)));
      }
    }
    t.events.drain();
    return t;
  }

  static TestCore funded() {
    return funded(1_000_000, 10_000);
  }

  /** A core that verifies real Ed25519 signatures. */
  static TestCore ed25519() {
    return new TestCore(
        CommandSigner.ED25519,
        ExecutionCore.genesis(
            Fixtures.genesis(), com.sajin.mizan.crypto.SignatureVerifier.ed25519()));
  }

  SignedCommand cmd(long account, OrderCommand body) {
    return signer.sign(Fixtures.CHAIN_ID, account, ++nonces[(int) account], body);
  }

  ExecResult exec(SignedCommand c) {
    return core.execute(c, events);
  }

  ExecResult exec(long account, OrderCommand body) {
    return exec(cmd(account, body));
  }

  ExecResult limit(long account, Side side, long price, long qty, TimeInForce tif) {
    return exec(account, new Place(0, I, side, OrderType.LIMIT, tif, price, qty));
  }

  ExecResult gtc(long account, Side side, long price, long qty) {
    return limit(account, side, price, qty, TimeInForce.GTC);
  }

  ExecResult market(long account, Side side, long collar, long qty) {
    return exec(account, new Place(0, I, side, OrderType.MARKET, TimeInForce.IOC, collar, qty));
  }

  ExecResult cancel(long account, long orderId) {
    return exec(account, new Cancel(orderId, I));
  }

  ExecResult ok(SignedCommand c) {
    ExecResult r = exec(c);
    if (r.status() != ExecResult.Status.ACCEPTED) {
      throw new AssertionError("expected ACCEPTED, got " + r + " for " + c.body());
    }
    return r;
  }

  long free(long account, int asset) {
    return core.free(account, asset);
  }

  long held(long account, int asset) {
    return core.held(account, asset);
  }
}
