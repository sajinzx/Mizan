package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.ExecResult.Status;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import org.junit.jupiter.api.Test;

/** Regression tests for defects 3 and 4 (§7). */
class FokTest {

  /**
   * Defect 3: the FOK pre-check must not count the taker's own resting orders. STP cancels them
   * instead of filling, so counting them let a FOK pass the check and then fill only partially.
   */
  @Test
  void ownRestingLiquidityDoesNotCount() {
    TestCore t = TestCore.funded();
    t.gtc(2, Side.SELL, 100, 6); // own liquidity
    t.gtc(3, Side.SELL, 100, 4); // other liquidity
    long before = t.core.nextOrderId();

    var r = t.limit(2, Side.BUY, 100, 8, TimeInForce.FOK);

    assertThat(r.status()).isEqualTo(Status.REJECTED);
    assertThat(t.core.nextOrderId()).isEqualTo(before);
    assertThat(t.core.restingOrders(TestCore.I, Side.SELL))
        .extracting(RestingOrder::remaining)
        .containsExactly(6L, 4L); // nothing traded, nothing cancelled

    // Book level: available excludes own orders.
    OrderBook b = t.core.book(TestCore.I);
    assertThat(b.availableQty(new LimitOrder(99, 2, 1, Side.BUY, TimeInForce.FOK, 100, 8)))
        .isEqualTo(4);
    assertThat(b.availableQty(new LimitOrder(99, 5, 1, Side.BUY, TimeInForce.FOK, 100, 8)))
        .isEqualTo(10);
  }

  @Test
  void fokFillsCompletelyWhenOthersCoverIt() {
    TestCore t = TestCore.funded();
    t.gtc(2, Side.SELL, 100, 6);
    t.gtc(3, Side.SELL, 101, 4);
    var r = t.limit(4, Side.BUY, 101, 10, TimeInForce.FOK);
    assertThat(r.status()).isEqualTo(Status.ACCEPTED);
    assertThat(t.events.trades()).hasSize(2);
    assertThat(t.core.restingOrders(TestCore.I, Side.SELL)).isEmpty();
  }

  /**
   * Defect 4: a market FOK buy could pass the pre-check and then fill partially under {@code
   * FillGuard}. Market orders must be IOC, and FOK is limit-only.
   */
  @Test
  void marketFokRejected() {
    // Buyer can afford only 5 of the 10 available lots: a market FOK would partially fill.
    TestCore t = TestCore.funded(500, 10_000);
    t.gtc(3, Side.SELL, 100, 10);
    long quoteBefore = t.free(2, TestCore.QUOTE);
    var r =
        t.exec(2, new Place(1, TestCore.I, Side.BUY, OrderType.MARKET, TimeInForce.FOK, 105, 10));
    assertThat(r.status()).isEqualTo(Status.REJECTED);
    assertThat(t.events.trades()).isEmpty();
    assertThat(t.free(2, TestCore.QUOTE)).isEqualTo(quoteBefore);
    assertThat(t.core.restingOrders(TestCore.I, Side.SELL)).hasSize(1);
  }
}
