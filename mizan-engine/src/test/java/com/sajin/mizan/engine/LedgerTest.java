package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Instrument;
import com.sajin.mizan.core.InsufficientBalanceException;
import com.sajin.mizan.core.InvalidOrderException;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.LimitExceededException;
import com.sajin.mizan.core.Limits;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.core.Trade;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerTest {
  static final int BASE = 1;
  static final int QUOTE = 2;

  /** Maker 10 bps, taker 30 bps, so fee rounding is visible. */
  static final Instrument IN = new Instrument(1, BASE, QUOTE, 1, 1, 10, 30, 1_000_000, 1_000_000);

  Ledger l;

  @BeforeEach
  void setUp() {
    l = new Ledger();
    l.register(2, new byte[44], 0);
    l.register(3, new byte[44], 0);
  }

  static LimitOrder buy(long account, long price, long qty) {
    return new LimitOrder(1, account, 1, Side.BUY, TimeInForce.GTC, price, qty);
  }

  static LimitOrder sell(long account, long price, long qty) {
    return new LimitOrder(2, account, 1, Side.SELL, TimeInForce.GTC, price, qty);
  }

  @Test
  void registerValidatesRangeAndDuplicates() {
    assertThatThrownBy(() -> l.register(0, new byte[44], 0))
        .isInstanceOf(InvalidOrderException.class);
    assertThatThrownBy(() -> l.register(Limits.MAX_ACCOUNT + 1, new byte[44], 0))
        .isInstanceOf(InvalidOrderException.class);
    assertThatThrownBy(() -> l.register(2, new byte[44], 0))
        .isInstanceOf(InvalidOrderException.class);
    l.register(Limits.MAX_ACCOUNT, new byte[44], 7);
    assertThat(l.lastNonce(Limits.MAX_ACCOUNT)).isEqualTo(7);
    assertThat(l.isRegistered(Limits.MAX_ACCOUNT)).isTrue();
    assertThat(l.pubKey(99)).isNull();
    assertThat(l.lastNonce(99)).isZero();
  }

  @Test
  void consumeNonceMustIncrease() {
    l.consumeNonce(2, 5);
    assertThat(l.lastNonce(2)).isEqualTo(5);
    assertThatThrownBy(() -> l.consumeNonce(2, 5)).isInstanceOf(InvariantViolationError.class);
    assertThatThrownBy(() -> l.consumeNonce(99, 1)).isInstanceOf(InvariantViolationError.class);
  }

  @Test
  void depositRules() {
    l.deposit(2, QUOTE, 500);
    assertThat(l.free(2, QUOTE)).isEqualTo(500);
    assertThat(l.supply(QUOTE)).isEqualTo(500);
    assertThatThrownBy(() -> l.deposit(2, QUOTE, 0)).isInstanceOf(InvalidOrderException.class);
    assertThatThrownBy(() -> l.deposit(2, QUOTE, Limits.MAX_DEPOSIT + 1))
        .isInstanceOf(LimitExceededException.class);
    assertThatThrownBy(() -> l.deposit(99, QUOTE, 1)).isInstanceOf(InvalidOrderException.class);
    assertThat(l.free(2, QUOTE)).isEqualTo(500);
  }

  @Test
  void supplyCapMakesOverflowImpossible() {
    // 2^62 / 2^50 = 4096 maximal deposits fit; one more unit is rejected before any mutation.
    for (int i = 0; i < 4095; i++) {
      l.deposit(2 + (i & 1), BASE, Limits.MAX_DEPOSIT);
    }
    l.deposit(2, BASE, Limits.MAX_DEPOSIT - 1);
    l.deposit(3, BASE, 1);
    assertThat(l.supply(BASE)).isEqualTo(Ledger.MAX_SUPPLY);
    assertThatThrownBy(() -> l.deposit(2, BASE, 1)).isInstanceOf(LimitExceededException.class);
    assertThat(l.total(BASE)).isEqualTo(Ledger.MAX_SUPPLY);
  }

  @Test
  void reserveAndReleaseForBothSides() {
    l.deposit(2, QUOTE, 1_000);
    l.deposit(3, BASE, 10);
    LimitOrder b = buy(2, 100, 7);
    l.reserve(b, IN);
    assertThat(l.free(2, QUOTE)).isEqualTo(300);
    assertThat(l.held(2, QUOTE)).isEqualTo(700);
    b.fill(3);
    l.release(b, IN); // releases only the remaining 4 * 100
    assertThat(l.free(2, QUOTE)).isEqualTo(700);
    assertThat(l.held(2, QUOTE)).isEqualTo(300);

    LimitOrder s = sell(3, 100, 10);
    l.reserve(s, IN);
    assertThat(l.held(3, BASE)).isEqualTo(10);
    l.release(s, IN);
    assertThat(l.free(3, BASE)).isEqualTo(10);
    assertThat(l.held(3, BASE)).isZero();
  }

  @Test
  void insufficientBalanceRejectsWithoutMutation() {
    l.deposit(2, QUOTE, 699);
    assertThatThrownBy(() -> l.reserve(buy(2, 100, 7), IN))
        .isInstanceOf(InsufficientBalanceException.class);
    assertThat(l.free(2, QUOTE)).isEqualTo(699);
    assertThat(l.held(2, QUOTE)).isZero();
  }

  @Test
  void marketBuyNeitherReservesNorReleases() {
    MarketOrder m = new MarketOrder(1, 2, 1, Side.BUY, 100, 5);
    l.reserve(m, IN);
    l.release(m, IN);
    assertThat(l.free(2, QUOTE)).isZero();
    assertThat(l.held(2, QUOTE)).isZero();
  }

  @Test
  void settleTakerBuyWithPriceImprovementAndFees() {
    l.deposit(2, QUOTE, 10_000);
    l.deposit(3, BASE, 100);
    l.reserve(buy(2, 105, 50), IN); // buyer escrows 5250
    l.reserve(sell(3, 100, 50), IN);
    // Buyer is taker at limit 105, fills 50 @ 100: cost 5000, refund 250.
    l.settle(new Trade(1, 1, 2, 1, 2, 3, Side.BUY, 100, 50, 105), IN);
    long baseFee = 50 * 30 / 10_000; // taker 30 bps on 50 base = 0 (floor)
    long quoteFee = 5_000 * 10 / 10_000; // maker 10 bps on 5000 quote = 5
    assertThat(l.held(2, QUOTE)).isZero();
    assertThat(l.free(2, QUOTE)).isEqualTo(10_000 - 5_000);
    assertThat(l.free(2, BASE)).isEqualTo(50 - baseFee);
    assertThat(l.held(3, BASE)).isZero();
    assertThat(l.free(3, QUOTE)).isEqualTo(5_000 - quoteFee);
    assertThat(l.free(Accounts.FEE_ACCOUNT, QUOTE)).isEqualTo(quoteFee);
    assertThat(l.total(QUOTE)).isEqualTo(10_000);
    assertThat(l.total(BASE)).isEqualTo(100);
  }

  @Test
  void settleMarketBuyPaysFromFreeAndTakerSellFees() {
    l.deposit(2, QUOTE, 100_000);
    l.deposit(3, BASE, 5_000);
    l.reserve(sell(3, 100, 5_000), IN);
    // Market buy (no escrow), seller is the resting maker.
    l.settle(new Trade(1, 1, 2, 9, 2, 3, Side.BUY, 100, 1_000, 0), IN);
    assertThat(l.free(2, QUOTE)).isEqualTo(0);
    assertThat(l.free(2, BASE)).isEqualTo(1_000 - 3); // taker 30 bps of 1000 = 3
    assertThat(l.free(3, QUOTE)).isEqualTo(100_000 - 100); // maker 10 bps of 100000 = 100
    assertThat(l.free(Accounts.FEE_ACCOUNT, BASE)).isEqualTo(3);
    assertThat(Ledger.bps(9_999, 1)).isZero();
    assertThat(Ledger.bps(10_000, 1)).isEqualTo(1);
  }

  @Test
  void overspendIsAnInvariantViolation() {
    l.deposit(3, BASE, 10);
    l.reserve(sell(3, 100, 10), IN);
    assertThatThrownBy(() -> l.settle(new Trade(1, 1, 2, 9, 2, 3, Side.BUY, 100, 1, 0), IN))
        .isInstanceOf(InvariantViolationError.class); // market buyer has no quote
  }

  @Test
  void copyIsIndependentAndZeroEntriesDisappear() {
    l.deposit(2, QUOTE, 100);
    Ledger c = l.copy();
    c.deposit(2, QUOTE, 1);
    c.consumeNonce(2, 9);
    assertThat(l.free(2, QUOTE)).isEqualTo(100);
    assertThat(l.lastNonce(2)).isZero();
    assertThat(c.supply(QUOTE)).isEqualTo(101);

    l.reserve(buy(2, 10, 10), IN);
    LimitOrder b = buy(2, 10, 10);
    l.release(b, IN);
    List<Long> keys = new ArrayList<>();
    l.forEachBalance((a, asset, free, held) -> keys.add(Ledger.key(a, asset)));
    assertThat(keys).containsExactly(Ledger.key(2, QUOTE));
    List<Long> accounts = new ArrayList<>();
    l.forEachAccount((a, nonce, pk) -> accounts.add(a));
    assertThat(accounts).containsExactly(2L, 3L);
  }

  @Test
  void restoreValidatesEntries() {
    Ledger r = new Ledger();
    assertThatThrownBy(() -> r.restoreBalance(2, 1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    r.restoreBalance(2, 1, 5, 0);
    assertThatThrownBy(() -> r.restoreBalance(2, 1, 5, 0))
        .isInstanceOf(IllegalArgumentException.class);
    r.restoreAccount(2, new byte[44], 3);
    assertThatThrownBy(() -> r.restoreAccount(2, new byte[44], 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(r.supply(1)).isEqualTo(5);
  }
}
