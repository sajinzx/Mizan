package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.InvalidOrderException;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderTest {

  static Place place(Side side, OrderType type, TimeInForce tif) {
    return new Place(9, 1, side, type, tif, 100, 5);
  }

  @ParameterizedTest
  @EnumSource(TimeInForce.class)
  void limitOrdersAlwaysEscrowAndRestOnlyIfGtcOrPostOnly(TimeInForce tif) {
    Order o = Order.of(1, 2, place(Side.BUY, OrderType.LIMIT, tif));
    assertThat(o).isInstanceOf(LimitOrder.class);
    assertThat(o.type()).isEqualTo(OrderType.LIMIT);
    assertThat(o.escrowsUpfront()).isTrue();
    assertThat(o.rests()).isEqualTo(tif == TimeInForce.GTC || tif == TimeInForce.POST_ONLY);
    assertThat(o.tif()).isEqualTo(tif);
  }

  @Test
  void marketOrdersNeverRestAndOnlySellsEscrow() {
    Order buy = Order.of(1, 2, place(Side.BUY, OrderType.MARKET, TimeInForce.IOC));
    Order sell = Order.of(1, 2, place(Side.SELL, OrderType.MARKET, TimeInForce.IOC));
    assertThat(buy).isInstanceOf(MarketOrder.class);
    assertThat(buy.type()).isEqualTo(OrderType.MARKET);
    assertThat(buy.rests()).isFalse();
    assertThat(sell.rests()).isFalse();
    assertThat(buy.escrowsUpfront()).isFalse();
    assertThat(sell.escrowsUpfront()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(value = TimeInForce.class, names = "IOC", mode = EnumSource.Mode.EXCLUDE)
  void marketMustBeIoc(TimeInForce tif) {
    assertThatThrownBy(() -> Order.of(1, 2, place(Side.BUY, OrderType.MARKET, tif)))
        .isInstanceOf(InvalidOrderException.class);
  }

  @Test
  void crossesUsesLimitOrCollar() {
    Order buy = Order.of(1, 2, place(Side.BUY, OrderType.LIMIT, TimeInForce.GTC));
    assertThat(buy.crosses(99)).isTrue();
    assertThat(buy.crosses(100)).isTrue();
    assertThat(buy.crosses(101)).isFalse();
    Order sell = Order.of(1, 2, place(Side.SELL, OrderType.MARKET, TimeInForce.IOC));
    assertThat(sell.crosses(101)).isTrue();
    assertThat(sell.crosses(100)).isTrue();
    assertThat(sell.crosses(99)).isFalse();
  }

  @Test
  void fillAndCopy() {
    Order o = Order.of(7, 3, place(Side.SELL, OrderType.LIMIT, TimeInForce.GTC));
    o.fill(2);
    assertThat(o.remaining()).isEqualTo(3);
    Order c = o.copy();
    c.fill(3);
    assertThat(o.remaining()).isEqualTo(3);
    assertThat(c.remaining()).isZero();
    assertThat(c.id()).isEqualTo(7);
    assertThat(c.accountId()).isEqualTo(3);
    assertThat(c.instrument()).isEqualTo(1);
    Order m = Order.of(8, 3, place(Side.BUY, OrderType.MARKET, TimeInForce.IOC));
    assertThat(m.copy().remaining()).isEqualTo(5);
    assertThatThrownBy(() -> o.fill(0)).isInstanceOf(InvariantViolationError.class);
    assertThatThrownBy(() -> o.fill(4)).isInstanceOf(InvariantViolationError.class);
  }
}
