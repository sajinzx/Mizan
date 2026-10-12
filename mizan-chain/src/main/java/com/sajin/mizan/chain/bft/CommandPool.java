package com.sajin.mizan.chain.bft;

import com.sajin.mizan.core.SignedCommand;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongUnaryOperator;

/**
 * Pending client commands of one validator (M8-T2): every command received from a local client or
 * gossiped by a peer, in arrival order, de-duplicated by {@code (account, nonce)}. Whoever is
 * proposer builds its block from the head of its pool. Committed and stale commands (nonce not
 * above the account's committed last nonce) are removed after every block. Commands from local
 * clients are re-gossiped until they commit, so a lost forward or a proposer change cannot strand
 * them. Thread-safe: gateways add, the consensus thread reads and removes.
 */
public final class CommandPool {
  /** Outcome of {@link #add}. */
  public enum Added {
    NEW,
    DUPLICATE,
    FULL
  }

  private record Key(long account, long nonce) {}

  private static final class Entry {
    final SignedCommand command;
    final boolean local;
    long lastGossipMillis;

    Entry(SignedCommand command, boolean local, long now) {
      this.command = command;
      this.local = local;
      this.lastGossipMillis = now;
    }
  }

  private final int capacity;
  private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();

  public CommandPool(int capacity) {
    this.capacity = capacity;
  }

  public synchronized Added add(SignedCommand c, boolean local, long nowMillis) {
    Key k = new Key(c.accountId(), c.nonce());
    if (entries.containsKey(k)) {
      return Added.DUPLICATE;
    }
    if (entries.size() >= capacity) {
      return Added.FULL;
    }
    entries.put(k, new Entry(c, local, nowMillis));
    return Added.NEW;
  }

  /** Up to {@code max} oldest commands, without removing them. */
  public synchronized List<SignedCommand> peek(int max) {
    List<SignedCommand> out = new ArrayList<>(Math.min(max, entries.size()));
    for (Entry e : entries.values()) {
      if (out.size() == max) {
        break;
      }
      out.add(e.command);
    }
    return out;
  }

  public synchronized void remove(Collection<SignedCommand> cmds) {
    for (SignedCommand c : cmds) {
      entries.remove(new Key(c.accountId(), c.nonce()));
    }
  }

  /** Drops commands whose nonce is no longer usable given the committed last nonce per account. */
  public synchronized int dropStale(LongUnaryOperator lastNonceOf) {
    int dropped = 0;
    Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator();
    while (it.hasNext()) {
      Key k = it.next().getKey();
      if (k.nonce() <= lastNonceOf.applyAsLong(k.account())) {
        it.remove();
        dropped++;
      }
    }
    return dropped;
  }

  /** Local commands not gossiped for {@code intervalMillis}; their gossip time is updated. */
  public synchronized List<SignedCommand> dueForGossip(
      long nowMillis, long intervalMillis, int max) {
    List<SignedCommand> out = new ArrayList<>();
    for (Entry e : entries.values()) {
      if (out.size() == max) {
        break;
      }
      if (e.local && nowMillis - e.lastGossipMillis >= intervalMillis) {
        e.lastGossipMillis = nowMillis;
        out.add(e.command);
      }
    }
    return out;
  }

  public synchronized int size() {
    return entries.size();
  }
}
