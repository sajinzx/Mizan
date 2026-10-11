package com.sajin.mizan.bench;

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
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.RestingOrder;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A deterministic, pre-signed workload for {@code ExecutionCore.execute}. Ten funded traders trade
 * one instrument around price 100: 60% GTC limits, 15% IOC limits, 10% market orders and 15%
 * cancels of the trader's own resting orders. Every command is accepted (checked while building),
 * so the run measures the accept path, matching and settlement rather than reject paths. Built by
 * simulating once on a scratch core, so cancels target real resting orders.
 */
final class BenchStream {
  static final long FIRST_TRADER = 2;
  static final int TRADERS = 10;

  final ExecutionCore initial;
  final SignedCommand[] commands;
  final int trades;

  private BenchStream(ExecutionCore initial, SignedCommand[] commands, int trades) {
    this.initial = initial;
    this.commands = commands;
    this.trades = trades;
  }

  static BenchStream build(int n, long seed, SignatureVerifier verifier, boolean verify) {
    ExecutionCore scratch = ExecutionCore.genesis(Fixtures.genesis(), verifier, verify);
    long[] nonces = new long[(int) FIRST_TRADER + TRADERS];
    for (long a = FIRST_TRADER; a < FIRST_TRADER + TRADERS; a++) {
      run(scratch, sign(nonces, a, new Register(Fixtures.accountSigner(a).publicKey())));
      run(scratch, sign(nonces, Accounts.ADMIN_ACCOUNT, new Deposit(a, Fixtures.QUOTE, 1L << 45)));
      run(scratch, sign(nonces, Accounts.ADMIN_ACCOUNT, new Deposit(a, Fixtures.BASE, 1L << 40)));
    }
    ExecutionCore initial = scratch.copy();
    SplittableRandom rnd = new SplittableRandom(seed);
    SignedCommand[] out = new SignedCommand[n];
    int[] trades = {0};
    for (int i = 0; i < n; i++) {
      long account = FIRST_TRADER + rnd.nextInt(TRADERS);
      Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
      long price = 95L + rnd.nextInt(11);
      long qty = 1L + rnd.nextInt(10);
      int roll = rnd.nextInt(100);
      OrderCommand body;
      List<RestingOrder> mine = roll >= 85 ? own(scratch, account) : List.of();
      if (roll >= 85 && !mine.isEmpty()) {
        body = new Cancel(mine.get(rnd.nextInt(mine.size())).id(), Fixtures.INSTRUMENT);
      } else if (roll >= 75) {
        long collar = side == Side.BUY ? price + 5 : price - 5;
        body =
            new Place(i, Fixtures.INSTRUMENT, side, OrderType.MARKET, TimeInForce.IOC, collar, qty);
      } else {
        TimeInForce tif = roll >= 60 ? TimeInForce.IOC : TimeInForce.GTC;
        body = new Place(i, Fixtures.INSTRUMENT, side, OrderType.LIMIT, tif, price, qty);
      }
      out[i] = sign(nonces, account, body);
      ExecResult r = scratch.execute(out[i], t -> trades[0]++);
      if (r.status() != ExecResult.Status.ACCEPTED) {
        throw new IllegalStateException("workload command " + i + " not accepted: " + r);
      }
    }
    return new BenchStream(initial, out, trades[0]);
  }

  private static List<RestingOrder> own(ExecutionCore core, long account) {
    List<RestingOrder> out = new ArrayList<>();
    for (Side s : Side.values()) {
      for (RestingOrder o : core.restingOrders(Fixtures.INSTRUMENT, s)) {
        if (o.account() == account) {
          out.add(o);
        }
      }
    }
    return out;
  }

  private static SignedCommand sign(long[] nonces, long account, OrderCommand body) {
    return CommandSigner.ED25519.sign(Fixtures.CHAIN_ID, account, ++nonces[(int) account], body);
  }

  private static void run(ExecutionCore core, SignedCommand c) {
    ExecResult r = core.execute(c, ExecutionCore.SILENT);
    if (r.status() != ExecResult.Status.ACCEPTED) {
      throw new IllegalStateException("setup command failed: " + r);
    }
  }
}
