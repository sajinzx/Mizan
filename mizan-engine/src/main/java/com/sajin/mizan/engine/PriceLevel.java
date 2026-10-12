package com.sajin.mizan.engine;

import com.sajin.mizan.core.InvariantViolationError;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * All resting orders at one price on one side, in FIFO (arrival) order, with a running total of
 * remaining quantity.
 *
 * <p>Storage (M10-T1): an intrusive doubly-linked list through {@link Order#prev}/{@link
 * Order#next}, so cancelling an order anywhere in the queue is O(1) instead of a linear scan, and
 * resting an order allocates no list node. The API is unchanged from the M2 {@code ArrayDeque}
 * version.
 */
public final class PriceLevel {
  private final long price;
  private Order head;
  private Order tail;
  private int size;
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
    return head == null;
  }

  public int size() {
    return size;
  }

  /** Appends {@code o} at the back of the queue. */
  public void add(Order o) {
    if (o.limitPrice() != price) {
      throw new InvariantViolationError("order " + o.id() + " added to wrong level " + price);
    }
    if (o.level != null) {
      throw new InvariantViolationError("order " + o.id() + " already rests on a level");
    }
    total = Math.addExact(total, o.remaining());
    o.level = this;
    o.prev = tail;
    o.next = null;
    if (tail == null) {
      head = o;
    } else {
      tail.next = o;
    }
    tail = o;
    size++;
  }

  /** The oldest order, or {@code null} if empty. */
  public Order peekFirst() {
    return head;
  }

  /** Removes the oldest order, subtracting whatever it still has remaining. */
  public Order removeFirst() {
    Order o = head;
    if (o == null) {
      throw new NoSuchElementException("empty level " + price);
    }
    unlink(o);
    return o;
  }

  /** Removes {@code o} (by identity) in O(1), subtracting its remaining quantity. */
  public boolean remove(Order o) {
    if (o.level != this) {
      return false;
    }
    unlink(o);
    return true;
  }

  private void unlink(Order o) {
    if (o.prev == null) {
      head = o.next;
    } else {
      o.prev.next = o.next;
    }
    if (o.next == null) {
      tail = o.prev;
    } else {
      o.next.prev = o.prev;
    }
    o.prev = null;
    o.next = null;
    o.level = null;
    size--;
    subtract(o.remaining());
  }

  /** Records that {@code qty} was filled from an order at this level. */
  public void reduce(long qty) {
    subtract(qty);
  }

  /** Orders in FIFO order. Read-only; must not be used while the level is being modified. */
  public Iterable<Order> orders() {
    return () ->
        new Iterator<>() {
          private Order cur = head;

          @Override
          public boolean hasNext() {
            return cur != null;
          }

          @Override
          public Order next() {
            if (cur == null) {
              throw new NoSuchElementException();
            }
            Order o = cur;
            cur = cur.next;
            return o;
          }
        };
  }

  private void subtract(long qty) {
    total -= qty;
    if (total < 0) {
      throw new InvariantViolationError("negative level total at " + price);
    }
  }
}
