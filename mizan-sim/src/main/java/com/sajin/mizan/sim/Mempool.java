package com.sajin.mizan.sim;

import com.sajin.mizan.core.SignedCommand;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Bounded FIFO buffer with de-duplication, guarded by a {@link ReentrantLock} and a {@link
 * Condition}. It holds commands to re-queue after a rollback and is the forwarding buffer of
 * non-proposers (§8 M3-T3, M8-T2). It is <b>not</b> on the hot path; the sequencer's inbound queue
 * is lock-free.
 *
 * <p>An item is a duplicate if an item with the same key is currently in the pool. For commands the
 * key is {@code (account, nonce)}, so re-forwarding the same command is idempotent.
 */
public final class Mempool<T> {

  /** Outcome of {@link #add}. */
  public enum AddResult {
    ADDED,
    DUPLICATE,
    FULL
  }

  /** De-duplication key of a signed command. */
  public record CommandKey(long account, long nonce) {}

  private final int capacity;
  private final Function<? super T, ?> keyOf;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private final ArrayDeque<T> items = new ArrayDeque<>();
  private final Set<Object> keys = new HashSet<>();

  public Mempool(int capacity, Function<? super T, ?> keyOf) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
    this.keyOf = Objects.requireNonNull(keyOf, "keyOf");
  }

  /** A command mempool keyed by {@code (account, nonce)}. */
  public static Mempool<SignedCommand> forCommands(int capacity) {
    return new Mempool<>(capacity, c -> new CommandKey(c.accountId(), c.nonce()));
  }

  /** Adds {@code item} unless it is a duplicate or the pool is full. Never blocks. */
  public AddResult add(T item) {
    Object key = Objects.requireNonNull(keyOf.apply(Objects.requireNonNull(item, "item")), "key");
    lock.lock();
    try {
      if (keys.contains(key)) {
        return AddResult.DUPLICATE;
      }
      if (items.size() >= capacity) {
        return AddResult.FULL;
      }
      items.addLast(item);
      keys.add(key);
      notEmpty.signal();
      return AddResult.ADDED;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Removes and returns up to {@code max} items in FIFO order. Waits up to {@code timeoutMs} for at
   * least one item; returns an empty list on timeout.
   */
  public List<T> drain(int max, long timeoutMs) throws InterruptedException {
    if (max <= 0) {
      throw new IllegalArgumentException("max must be positive");
    }
    long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    lock.lock();
    try {
      while (items.isEmpty()) {
        if (remaining <= 0) {
          return List.of();
        }
        remaining = notEmpty.awaitNanos(remaining);
      }
      List<T> out = new ArrayList<>(Math.min(max, items.size()));
      while (out.size() < max && !items.isEmpty()) {
        T item = items.pollFirst();
        keys.remove(keyOf.apply(item));
        out.add(item);
      }
      return out;
    } finally {
      lock.unlock();
    }
  }

  public int size() {
    lock.lock();
    try {
      return items.size();
    } finally {
      lock.unlock();
    }
  }
}
