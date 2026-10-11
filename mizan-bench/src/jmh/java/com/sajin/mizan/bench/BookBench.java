package com.sajin.mizan.bench;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.engine.FillGuard;
import com.sajin.mizan.engine.LimitOrder;
import com.sajin.mizan.engine.MatchSink;
import com.sajin.mizan.engine.Order;
import com.sajin.mizan.engine.OrderBook;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * {@code OrderBook} operations by depth: {@code depth} price levels per side, {@value
 * #ORDERS_PER_LEVEL} resting orders per level. The book stays in a steady state:
 *
 * <ul>
 *   <li>{@code addCancel}: rest one bid at a level spread across the book, then cancel it;
 *   <li>{@code matchOne}: a 1-lot buy that fills against the best ask (makers are huge, so the
 *       level never empties).
 * </ul>
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BookBench {
  static final int ORDERS_PER_LEVEL = 4;
  static final long MID = 1_000_000;
  static final long TRADER = 7;
  static final long TAKER = 8;

  @Param({"10", "100", "1000"})
  public int depth;

  private OrderBook book;
  private long nextId;
  private long seq;
  private MatchSink sink;

  @Setup
  public void setup(Blackhole bh) {
    book = new OrderBook(1);
    nextId = 1;
    for (int lvl = 0; lvl < depth; lvl++) {
      for (int k = 0; k < ORDERS_PER_LEVEL; k++) {
        book.rest(
            new LimitOrder(nextId++, TRADER, 1, Side.BUY, TimeInForce.GTC, MID - 1 - lvl, 10));
        book.rest(
            new LimitOrder(nextId++, TRADER, 1, Side.SELL, TimeInForce.GTC, MID + lvl, 1L << 40));
      }
    }
    sink =
        new MatchSink() {
          @Override
          public void onFill(Trade t) {
            bh.consume(t);
          }

          @Override
          public void onSelfTradeCancel(Order maker) {
            bh.consume(maker);
          }

          @Override
          public void onDelta(Side side, long price, long aggQty) {
            bh.consume(aggQty);
          }
        };
  }

  @Benchmark
  @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
  public Order addCancel() {
    long id = nextId++;
    long price = MID - 1 - (id % depth);
    book.rest(new LimitOrder(id, TRADER, 1, Side.BUY, TimeInForce.GTC, price, 1));
    return book.cancel(id, TRADER);
  }

  @Benchmark
  @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
  public long matchOne() {
    LimitOrder taker = new LimitOrder(nextId++, TAKER, 1, Side.BUY, TimeInForce.IOC, MID, 1);
    seq = book.match(taker, seq, FillGuard.UNLIMITED, sink);
    return seq;
  }
}
