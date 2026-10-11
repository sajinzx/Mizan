package com.sajin.mizan.testkit;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.RestingOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Canonical, fully comparable dump of engine state: counters, non-zero balances by {@code (account,
 * asset)}, accounts by id, and resting orders per instrument (ascending), bids then asks, in
 * priority order. Records compare by value.
 */
public record StateDump(
    long nextOrderId,
    long nextTradeSeq,
    List<Balance> balances,
    List<Account> accounts,
    List<Resting> book) {

  public record Balance(long account, int asset, long free, long held) {}

  public record Account(long account, long lastNonce, String pubKeyHex) {}

  public record Resting(
      int instrument, Side side, long price, long id, long account, long remaining) {}

  public StateDump {
    balances = List.copyOf(balances);
    accounts = List.copyOf(accounts);
    book = List.copyOf(book);
  }

  public static StateDump of(ExecutionCore core) {
    List<Balance> balances = new ArrayList<>();
    core.forEachBalance((a, asset, free, held) -> balances.add(new Balance(a, asset, free, held)));
    List<Account> accounts = new ArrayList<>();
    core.forEachAccount(
        (a, nonce, pk) -> accounts.add(new Account(a, nonce, HexFormat.of().formatHex(pk))));
    List<Resting> book = new ArrayList<>();
    for (int instrument : core.instrumentIds()) {
      for (Side s : List.of(Side.BUY, Side.SELL)) {
        for (RestingOrder o : core.restingOrders(instrument, s)) {
          book.add(new Resting(instrument, s, o.price(), o.id(), o.account(), o.remaining()));
        }
      }
    }
    return new StateDump(core.nextOrderId(), core.nextTradeSeq(), balances, accounts, book);
  }

  /** Free + held of {@code asset} for {@code account}; {@code 0} if absent. */
  public long total(long account, int asset) {
    for (Balance b : balances) {
      if (b.account() == account && b.asset() == asset) {
        return b.free() + b.held();
      }
    }
    return 0;
  }
}
