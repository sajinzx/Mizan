package com.sajin.mizan.engine;

import com.sajin.mizan.core.InvalidOrderException;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Price-time priority book for one instrument (§6.2). Bids are best-first descending, asks
 * best-first ascending, and FIFO within a level is arrival order (== order id). The {@code index}
 * map is used for lookups only and never iterated.
 */
public final class OrderBook {
  private final int instrument;
  private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Collections.reverseOrder());
  private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();

  // Primitive-keyed (M10-T1: no Long boxing per lookup). Declared with its concrete type so
  // ArchRulesTest can prove it is never iterated.
  private final Long2ObjectHashMap<Order> index = new Long2ObjectHashMap<>();

  private StateDirt dirt = StateDirt.NONE;

  /** Where changed order ids are reported (state root v2); {@code NONE} for v1. */
  void setDirt(StateDirt d) {
    this.dirt = d;
  }

  /** One aggregated depth entry. */
  public record Level(long price, long qty) {}

  public OrderBook(int instrument) {
    this.instrument = instrument;
  }

  public int instrument() {
    return instrument;
  }

  private NavigableMap<Long, PriceLevel> side(Side s) {
    return s == Side.BUY ? bids : asks;
  }

  /**
   * Matches {@code taker} against the opposite side until it is filled, the book stops crossing, or
   * {@code guard} allows no more. Resting orders of the taker's own account are cancelled (STP)
   * rather than traded against.
   *
   * @return the next unused trade sequence number
   */
  public long match(Order taker, long nextSeq, FillGuard guard, MatchSink sink) {
    var opp = side(taker.side().opposite());
    while (taker.remaining() > 0 && !opp.isEmpty()) {
      var best = opp.firstEntry();
      long px = best.getKey();
      if (!taker.crosses(px)) {
        break;
      }
      PriceLevel level = best.getValue();
      Order maker = level.peekFirst();
      if (maker.accountId() == taker.accountId()) {
        dirt.order(maker.id());
        level.removeFirst();
        index.remove(maker.id());
        if (level.isEmpty()) {
          opp.pollFirstEntry();
        }
        sink.onSelfTradeCancel(maker);
        sink.onDelta(maker.side(), px, level.total());
        continue;
      }
      long qty = Math.min(Math.min(maker.remaining(), taker.remaining()), guard.maxQty(taker, px));
      if (qty <= 0) {
        break; // taker cannot afford another lot
      }
      maker.fill(qty);
      dirt.order(maker.id());
      taker.fill(qty);
      level.reduce(qty);
      Order buyer = taker.side() == Side.BUY ? taker : maker;
      Order seller = buyer == taker ? maker : taker;
      long escrowPx = buyer.escrowsUpfront() ? buyer.limitPrice() : 0;
      sink.onFill(
          new Trade(
              nextSeq++,
              instrument,
              maker.id(),
              taker.id(),
              buyer.accountId(),
              seller.accountId(),
              taker.side(),
              px,
              qty,
              escrowPx));
      if (maker.remaining() == 0) {
        level.removeFirst();
        index.remove(maker.id());
      }
      if (level.isEmpty()) {
        opp.pollFirstEntry();
      }
      sink.onDelta(maker.side(), px, level.total());
    }
    return nextSeq;
  }

  /**
   * Quantity available to {@code taker} at crossing prices, excluding the taker's own resting
   * orders, which STP would cancel instead of filling (defect 3). Read-only. Stops counting once
   * the taker's quantity is covered.
   */
  public long availableQty(Order taker) {
    long sum = 0;
    for (PriceLevel lvl : side(taker.side().opposite()).values()) {
      if (!taker.crosses(lvl.price())) {
        break;
      }
      for (Order o : lvl.orders()) {
        if (o.accountId() != taker.accountId()) {
          sum = Math.addExact(sum, o.remaining());
        }
      }
      if (sum >= taker.remaining()) {
        return sum;
      }
    }
    return sum;
  }

  /** Whether {@code taker} would cross the best opposite price (any account). */
  public boolean wouldCross(Order taker) {
    var opp = side(taker.side().opposite());
    return !opp.isEmpty() && taker.crosses(opp.firstKey());
  }

  /** Adds a resting order at the back of its price level. */
  public void rest(Order o) {
    if (o.instrument() != instrument || o.remaining() <= 0 || !o.rests()) {
      throw new InvariantViolationError("cannot rest order " + o.id());
    }
    if (index.putIfAbsent(o.id(), o) != null) {
      throw new InvariantViolationError("duplicate order id " + o.id());
    }
    dirt.order(o.id());
    side(o.side()).computeIfAbsent(o.limitPrice(), PriceLevel::new).add(o);
  }

  /**
   * Removes a resting order owned by {@code account}.
   *
   * @throws InvalidOrderException if no such order rests here or it belongs to another account;
   *     nothing changes in that case
   */
  public Order cancel(long orderId, long account) {
    Order o = index.get(orderId);
    if (o == null) {
      throw new InvalidOrderException("unknown order " + orderId);
    }
    if (o.accountId() != account) {
      throw new InvalidOrderException("order " + orderId + " belongs to another account");
    }
    index.remove(orderId);
    dirt.order(orderId);
    var levels = side(o.side());
    PriceLevel level = levels.get(o.limitPrice());
    if (level == null || !level.remove(o)) {
      throw new InvariantViolationError("indexed order " + orderId + " missing from its level");
    }
    if (level.isEmpty()) {
      levels.remove(o.limitPrice());
    }
    return o;
  }

  /** Aggregate quantity at {@code (side, price)}; {@code 0} if no level exists. */
  public long levelTotal(Side s, long price) {
    PriceLevel l = side(s).get(price);
    return l == null ? 0 : l.total();
  }

  /** Up to {@code n} best levels of side {@code s}. */
  public List<Level> depth(Side s, int n) {
    List<Level> out = new ArrayList<>();
    for (PriceLevel l : side(s).values()) {
      if (out.size() == n) {
        break;
      }
      out.add(new Level(l.price(), l.total()));
    }
    return out;
  }

  /** Levels of side {@code s}, best first. Read-only. */
  public Iterable<PriceLevel> levels(Side s) {
    return Collections.unmodifiableCollection(side(s).values());
  }

  public Order find(long orderId) {
    return index.get(orderId);
  }

  public int orderCount() {
    return index.size();
  }

  /** Every resting order sorted by order id: deterministic regardless of price structure. */
  public List<Order> orders() {
    List<Order> out = new ArrayList<>(index.size());
    for (Side s : Side.values()) {
      for (PriceLevel l : side(s).values()) {
        for (Order o : l.orders()) {
          out.add(o);
        }
      }
    }
    out.sort((a, b) -> Long.compare(a.id(), b.id()));
    return out;
  }

  /** Deep copy: new orders, levels and index in the same FIFO order. */
  public OrderBook copy() {
    OrderBook c = new OrderBook(instrument);
    for (Side s : Side.values()) {
      for (PriceLevel l : side(s).values()) {
        for (Order o : l.orders()) {
          c.rest(o.copy());
        }
      }
    }
    return c;
  }
}
