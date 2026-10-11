package com.sajin.mizan.net;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Node-local cache of command outcomes keyed by {@code (account, nonce)} (§5.5, ADR 0003, defect
 * 8). A duplicate {@code ORDER} whose key is cached gets the cached answer without being
 * resubmitted, and {@code QUERY_STATUS} reads from here.
 *
 * <p>Bounded LRU: at most {@code perAccount} entries per account and {@code total} overall, with
 * O(1) eviction (a per-account LRU plus a global LRU). This is <b>not</b> consensus state: it is
 * rebuilt from the last committed blocks on startup, and an evicted entry only makes a retry less
 * informative (the engine still rejects a stale nonce). Thread-safe.
 */
public final class ResultCache {
  /** Outcome state, mirroring the {@code STATUS} frame. */
  public enum State {
    PROVISIONAL,
    REJECTED,
    FINAL
  }

  /**
   * A cached outcome. For {@code FINAL}, {@code finalStatus} is {@code Messages.Final.ACCEPTED} or
   * {@code REJECTED}; {@code height} is 0 until final.
   */
  public record Entry(
      State state, long orderId, int reasonCode, String reason, long height, int finalStatus) {}

  private record Key(long account, long nonce) {}

  private final int perAccount;
  private final int total;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Long, LinkedHashMap<Long, Entry>> byAccount = new HashMap<>();
  private final LinkedHashMap<Key, Boolean> global = new LinkedHashMap<>(16, 0.75f, true);

  public ResultCache(int perAccount, int total) {
    if (perAccount <= 0 || total <= 0) {
      throw new IllegalArgumentException("capacities must be positive");
    }
    this.perAccount = perAccount;
    this.total = total;
  }

  /** The spec defaults: 1,024 per account, 1M total. */
  public static ResultCache withDefaults() {
    return new ResultCache(1024, 1 << 20);
  }

  public Entry get(long account, long nonce) {
    lock.lock();
    try {
      LinkedHashMap<Long, Entry> m = byAccount.get(account);
      Entry e = m == null ? null : m.get(nonce);
      if (e != null) {
        global.get(new Key(account, nonce)); // touch
      }
      return e;
    } finally {
      lock.unlock();
    }
  }

  /** Inserts or replaces. A {@code FINAL} entry is never downgraded. */
  public void put(long account, long nonce, Entry e) {
    lock.lock();
    try {
      LinkedHashMap<Long, Entry> m =
          byAccount.computeIfAbsent(account, a -> new LinkedHashMap<>(16, 0.75f, true));
      Entry old = m.get(nonce);
      if (old != null && old.state() == State.FINAL && e.state() != State.FINAL) {
        return;
      }
      m.put(nonce, e);
      global.put(new Key(account, nonce), Boolean.TRUE);
      if (m.size() > perAccount) {
        Iterator<Long> it = m.keySet().iterator();
        long victim = it.next();
        it.remove();
        global.remove(new Key(account, victim));
      }
      while (global.size() > total) {
        Iterator<Key> it = global.keySet().iterator();
        Key victim = it.next();
        it.remove();
        LinkedHashMap<Long, Entry> vm = byAccount.get(victim.account());
        vm.remove(victim.nonce());
        if (vm.isEmpty()) {
          byAccount.remove(victim.account());
        }
      }
    } finally {
      lock.unlock();
    }
  }

  public int size() {
    lock.lock();
    try {
      return global.size();
    } finally {
      lock.unlock();
    }
  }
}
