package com.sajin.mizan.engine;

import com.sajin.mizan.core.InvariantViolationError;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;

/**
 * All resting orders at one price on one side, in FIFO (arrival) order, with a running total of
 * remaining quantity. The storage is hidden so the M10 intrusive list can replace it without API
 * changes.
 */
public final class PriceLevel {
  private final long price;
  private final ArrayDeque<Order> orders = new ArrayDeque<>();
  private long total;

  public PriceLevel(long price) {
    this.price = price;
  }

  public long price() {
    return price;
  }

  /** Sum of {@code remaining} over all orders at this level. */
  public long total() {
    return total;
  }

  public boolean isEmpty() {
    return orders.isEmpty();
  }

  public int size() {
    return orders.size();
  }

  /** Appends {@code o} at the back of the queue. */
  public void add(Order o) {
    if (o.limitPrice() != price) {
      throw new InvariantViolationError("order " + o.id() + " added to wrong level " + price);
    }
    orders.addLast(o);
    total = Math.addExact(total, o.remaining());
  }

  /** The oldest order, or {@code null} if empty. */
  public Order peekFirst() {
    return orders.peekFirst();
  }

  /** Removes the oldest order, subtracting whatever it still has remaining. */
  public Order removeFirst() {
    Order o = orders.removeFirst();
    subtract(o.remaining());
    return o;
  }

  /** Removes {@code o} (by identity), subtracting its remaining quantity. */
  public boolean remove(Order o) {
    Iterator<Order> it = orders.iterator();
    while (it.hasNext()) {
      if (it.next() == o) {
        it.remove();
        subtract(o.remaining());
        return true;
      }
    }
    return false;
  }

  /** Records that {@code qty} was filled from an order at this level. */
  public void reduce(long qty) {
    subtract(qty);
  }

  /** Orders in FIFO order. Read-only. */
  public Iterable<Order> orders() {
    return Collections.unmodifiableCollection(orders);
  }

  private void subtract(long qty) {
    total -= qty;
    if (total < 0) {
      throw new InvariantViolationError("negative level total at " + price);
    }
  }
}
