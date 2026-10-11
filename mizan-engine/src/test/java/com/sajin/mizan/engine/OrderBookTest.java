package com.sajin.mizan.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.InvalidOrderException;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.core.Trade;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderBookTest {
  OrderBook book;
  List<Trade> fills;
  List<Order> stp;
  List<String> deltas;
  MatchSink sink;

  @BeforeEach
  void setUp() {
    book = new OrderBook(1);
    fills = new ArrayList<>();
    stp = new ArrayList<>();
    deltas = new ArrayList<>();
    sink =
        new MatchSink() {
          @Override
          public void onFill(Trade t) {
            fills.add(t);
          }

          @Override
          public void onSelfTradeCancel(Order maker) {
            stp.add(maker);
          }

          @Override
          public void onDelta(Side side, long price, long aggQty) {
            deltas.add(side + "@" + price + "=" + aggQty);
          }
        };
  }

  static LimitOrder lim(long id, long account, Side side, long price, long qty) {
    return new LimitOrder(id, account, 1, side, TimeInForce.GTC, price, qty);
  }

  @Test
  void pricePriorityThenFifo() {
    book.rest(lim(1, 2, Side.SELL, 101, 5));
    book.rest(lim(2, 3, Side.SELL, 100, 5));
    book.rest(lim(3, 4, Side.SELL, 100, 5));
    LimitOrder taker = lim(10, 5, Side.BUY, 101, 12);
    long next = book.match(taker, 1, FillGuard.UNLIMITED, sink);

    assertThat(next).isEqualTo(4);
    assertThat(fills).extracting(Trade::makerOrderId).containsExactly(2L, 3L, 1L);
    assertThat(fills).extracting(Trade::price).containsExactly(100L, 100L, 101L);
    assertThat(fills).extracting(Trade::qty).containsExactly(5L, 5L, 2L);
    assertThat(fills.get(0).buyEscrowPrice()).isEqualTo(101); // taker buyer escrowed at its limit
    assertThat(fills.get(0).buyAccount()).isEqualTo(5);
    assertThat(fills.get(0).sellAccount()).isEqualTo(3);
    assertThat(deltas).containsExactly("SELL@100=5", "SELL@100=0", "SELL@101=3");
    assertThat(taker.remaining()).isZero();
    assertThat(book.orderCount()).isEqualTo(1);
    assertThat(book.find(1).remaining()).isEqualTo(3);
  }

  @Test
  void stopsWhenPriceNoLongerCrosses() {
    book.rest(lim(1, 2, Side.BUY, 99, 5));
    LimitOrder taker = lim(2, 3, Side.SELL, 100, 5);
    book.match(taker, 1, FillGuard.UNLIMITED, sink);
    assertThat(fills).isEmpty();
    assertThat(taker.remaining()).isEqualTo(5);
    assertThat(book.wouldCross(taker)).isFalse();
    assertThat(book.wouldCross(lim(3, 3, Side.SELL, 99, 1))).isTrue();
    assertThat(book.wouldCross(lim(3, 3, Side.BUY, 200, 1))).isFalse(); // no asks at all
  }

  @Test
  void selfTradePreventionCancelsRestingOrder() {
    book.rest(lim(1, 2, Side.SELL, 100, 5));
    book.rest(lim(2, 3, Side.SELL, 100, 5));
    LimitOrder taker = lim(3, 2, Side.BUY, 100, 5);
    book.match(taker, 1, FillGuard.UNLIMITED, sink);
    assertThat(stp).extracting(Order::id).containsExactly(1L);
    assertThat(fills).extracting(Trade::makerOrderId).containsExactly(2L);
    assertThat(deltas).containsExactly("SELL@100=5", "SELL@100=0");
    assertThat(book.orderCount()).isZero();
  }

  @Test
  void guardOfZeroStopsMatching() {
    book.rest(lim(1, 2, Side.SELL, 100, 5));
    LimitOrder taker = lim(2, 3, Side.BUY, 100, 5);
    book.match(taker, 1, (t, px) -> 2, sink);
    assertThat(fills).extracting(Trade::qty).containsExactly(2L, 2L, 1L); // guard caps each fill
    LimitOrder second = lim(3, 4, Side.BUY, 100, 5);
    book.rest(lim(4, 2, Side.SELL, 100, 5));
    fills.clear();
    book.match(second, 9, (t, px) -> 0, sink);
    assertThat(fills).isEmpty();
  }

  @Test
  void cancelChecksOwnerAndCleansUpLevels() {
    book.rest(lim(1, 2, Side.BUY, 99, 5));
    assertThatThrownBy(() -> book.cancel(1, 3)).isInstanceOf(InvalidOrderException.class);
    assertThatThrownBy(() -> book.cancel(42, 2)).isInstanceOf(InvalidOrderException.class);
    assertThat(book.orderCount()).isEqualTo(1);
    assertThat(book.cancel(1, 2).id()).isEqualTo(1);
    assertThat(book.orderCount()).isZero();
    assertThat(book.levelTotal(Side.BUY, 99)).isZero();
    assertThat(book.levels(Side.BUY)).isEmpty();
  }

  @Test
  void restRejectsDuplicatesAndNonRestingOrders() {
    book.rest(lim(1, 2, Side.BUY, 99, 5));
    assertThatThrownBy(() -> book.rest(lim(1, 2, Side.BUY, 98, 5)))
        .isInstanceOf(InvariantViolationError.class);
    assertThatThrownBy(() -> book.rest(new LimitOrder(5, 2, 1, Side.BUY, TimeInForce.IOC, 99, 5)))
        .isInstanceOf(InvariantViolationError.class);
    assertThatThrownBy(() -> book.rest(new MarketOrder(6, 2, 1, Side.BUY, 99, 5)))
        .isInstanceOf(InvariantViolationError.class);
    assertThatThrownBy(() -> book.rest(new LimitOrder(7, 2, 9, Side.BUY, TimeInForce.GTC, 9, 5)))
        .isInstanceOf(InvariantViolationError.class);
  }

  @Test
  void depthOrdersAndCopy() {
    book.rest(lim(5, 2, Side.BUY, 98, 1));
    book.rest(lim(2, 2, Side.BUY, 99, 2));
    book.rest(lim(3, 3, Side.BUY, 99, 3));
    book.rest(lim(4, 3, Side.SELL, 103, 4));
    assertThat(book.depth(Side.BUY, 5))
        .containsExactly(new OrderBook.Level(99, 5), new OrderBook.Level(98, 1));
    assertThat(book.depth(Side.BUY, 1)).containsExactly(new OrderBook.Level(99, 5));
    assertThat(book.depth(Side.SELL, 5)).containsExactly(new OrderBook.Level(103, 4));
    assertThat(book.orders()).extracting(Order::id).containsExactly(2L, 3L, 4L, 5L);
    assertThat(book.instrument()).isEqualTo(1);

    OrderBook copy = book.copy();
    copy.cancel(2, 2);
    copy.find(3).fill(1);
    assertThat(book.find(2)).isNotNull();
    assertThat(book.find(3).remaining()).isEqualTo(3);
    assertThat(copy.orders()).extracting(Order::id).containsExactly(3L, 4L, 5L);
  }
}
