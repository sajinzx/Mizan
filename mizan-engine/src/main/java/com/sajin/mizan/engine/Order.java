package com.sajin.mizan.engine;

import com.sajin.mizan.core.InvalidOrderException;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.TimeInForce;

/**
 * An order inside the engine (§5.4). Identity, side and price are immutable; only {@link
 * #remaining()} changes, through {@link #fill(long)}.
 */
public abstract sealed class Order permits LimitOrder, MarketOrder {
  private final long id;
  private final long accountId;
  private final int instrument;
  private final Side side;
  private final TimeInForce tif;
  private final long price;
  private long remaining;

  // Intrusive FIFO links (M10-T1), owned by the PriceLevel this order rests on; null otherwise.
  // Never copied: a copied order is linked into the copied book's own levels.
  PriceLevel level;
  Order prev;
  Order next;

  Order(
      long id,
      long accountId,
      int instrument,
      Side side,
      TimeInForce tif,
      long price,
      long remaining) {
    this.id = id;
    this.accountId = accountId;
    this.instrument = instrument;
    this.side = side;
    this.tif = tif;
    this.price = price;
    this.remaining = remaining;
  }

  /**
   * Builds the engine order for a {@code Place} command with engine-assigned {@code id}.
   *
   * @throws InvalidOrderException if {@code MARKET} is not {@code IOC} or {@code FOK} is not {@code
   *     LIMIT} (defect 4)
   */
  public static Order of(long id, long accountId, Place p) {
    if (p.type() == OrderType.MARKET) {
      if (p.tif() != TimeInForce.IOC) {
        throw new InvalidOrderException("market orders must be IOC");
      }
      return new MarketOrder(id, accountId, p.instrument(), p.side(), p.price(), p.qty());
    }
    return new LimitOrder(id, accountId, p.instrument(), p.side(), p.tif(), p.price(), p.qty());
  }

  public final long id() {
    return id;
  }

  public final long accountId() {
    return accountId;
  }

  public final int instrument() {
    return instrument;
  }

  public final Side side() {
    return side;
  }

  public final TimeInForce tif() {
    return tif;
  }

  public final long remaining() {
    return remaining;
  }

  /** The limit price for limit orders; the collar (worst acceptable price) for market orders. */
  public final long limitPrice() {
    return price;
  }

  public abstract OrderType type();

  /** Whether an unfilled remainder rests on the book. */
  public abstract boolean rests();

  /** Whether the full order value is escrowed before matching. */
  public abstract boolean escrowsUpfront();

  /** Whether this order, as taker, would trade against a resting order at {@code restingPrice}. */
  public final boolean crosses(long restingPrice) {
    return side == Side.BUY ? restingPrice <= price : restingPrice >= price;
  }

  final void fill(long qty) {
    if (qty <= 0 || qty > remaining) {
      throw new InvariantViolationError(
          "bad fill " + qty + " on order " + id + " with remaining " + remaining);
    }
    remaining -= qty;
  }

  abstract Order copy();
}
