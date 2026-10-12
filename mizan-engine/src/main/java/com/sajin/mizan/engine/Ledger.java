package com.sajin.mizan.engine;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Instrument;
import com.sajin.mizan.core.InsufficientBalanceException;
import com.sajin.mizan.core.InvalidOrderException;
import com.sajin.mizan.core.InvariantViolationError;
import com.sajin.mizan.core.LimitExceededException;
import com.sajin.mizan.core.Limits;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;
import java.util.Map;
import java.util.TreeMap;

/**
 * Multi-asset escrow ledger with account metadata (§6.1).
 *
 * <p>Every rejecting check runs before any mutation. Total supply per asset is capped at {@link
 * #MAX_SUPPLY} (ADR 0008), so no balance or sum of balances can overflow. An overflow during
 * settlement would therefore be a bug and raises {@link InvariantViolationError}.
 */
public final class Ledger {
  /** Upper bound on the sum of all deposits of one asset. */
  public static final long MAX_SUPPLY = 1L << 62;

  /** Mutable free/held pair. Entries with both zero are removed (canonical state). */
  static final class Bal {
    long free;
    long held;

    Bal(long free, long held) {
      this.free = free;
      this.held = held;
    }
  }

  /** Account metadata. */
  static final class Meta {
    final byte[] pubKey;
    long lastNonce;

    Meta(byte[] pubKey, long lastNonce) {
      this.pubKey = pubKey;
      this.lastNonce = lastNonce;
    }
  }

  private final TreeMap<Long, Bal> bal = new TreeMap<>();
  private final TreeMap<Long, Meta> meta = new TreeMap<>();
  private final TreeMap<Integer, Long> supply = new TreeMap<>();
  private StateDirt dirt = StateDirt.NONE;

  /** Where changed keys are reported (state root v2); {@code NONE} for v1. */
  void setDirt(StateDirt d) {
    this.dirt = d;
  }

  public static long key(long account, int asset) {
    return (account << 16) | asset;
  }

  // ------------------------------------------------------------------ reads (never create)

  public long free(long account, int asset) {
    Bal b = bal.get(key(account, asset));
    return b == null ? 0 : b.free;
  }

  public long held(long account, int asset) {
    Bal b = bal.get(key(account, asset));
    return b == null ? 0 : b.held;
  }

  public boolean isRegistered(long account) {
    return meta.containsKey(account);
  }

  /** The registered key, or {@code null}. Callers must not mutate it. */
  public byte[] pubKey(long account) {
    Meta m = meta.get(account);
    return m == null ? null : m.pubKey;
  }

  /** Last consumed nonce; {@code 0} for unregistered accounts. */
  public long lastNonce(long account) {
    Meta m = meta.get(account);
    return m == null ? 0 : m.lastNonce;
  }

  /** Sum of {@code free + held} over all accounts (including the fee account) for {@code asset}. */
  public long total(int asset) {
    long sum = 0;
    for (Map.Entry<Long, Bal> e : bal.entrySet()) {
      if ((int) (e.getKey() & 0xFFFF) == asset) {
        sum = Math.addExact(sum, Math.addExact(e.getValue().free, e.getValue().held));
      }
    }
    return sum;
  }

  /** Sum of successful deposits of {@code asset}. */
  public long supply(int asset) {
    return supply.getOrDefault(asset, 0L);
  }

  /** Balances in key order: {@code (account << 16 | asset) -> [free, held]}. */
  void forEachBalance(BalanceVisitor v) {
    for (Map.Entry<Long, Bal> e : bal.entrySet()) {
      long k = e.getKey();
      v.visit(k >>> 16, (int) (k & 0xFFFF), e.getValue().free, e.getValue().held);
    }
  }

  /** Accounts in id order. */
  void forEachAccount(AccountVisitor v) {
    for (Map.Entry<Long, Meta> e : meta.entrySet()) {
      v.visit(e.getKey(), e.getValue().lastNonce, e.getValue().pubKey);
    }
  }

  /** Visits one balance entry. */
  @FunctionalInterface
  public interface BalanceVisitor {
    void visit(long account, int asset, long free, long held);
  }

  /** Visits one account. */
  @FunctionalInterface
  public interface AccountVisitor {
    void visit(long account, long lastNonce, byte[] pubKey);
  }

  // ------------------------------------------------------------------ account operations

  /**
   * Registers {@code account} with {@code pubKey} and {@code nonce} as its last nonce.
   *
   * @throws InvalidOrderException if the id is out of range or already registered
   */
  public void register(long account, byte[] pubKey, long nonce) {
    if (account <= 0 || account > Limits.MAX_ACCOUNT) {
      throw new InvalidOrderException("account id out of range: " + account);
    }
    if (meta.containsKey(account)) {
      throw new InvalidOrderException("account already registered: " + account);
    }
    meta.put(account, new Meta(pubKey.clone(), nonce));
    dirt.meta(account);
  }

  /** Burns {@code nonce}: the only mutation allowed before business validation (§6.3). */
  public void consumeNonce(long account, long nonce) {
    Meta m = meta.get(account);
    if (m == null || nonce <= m.lastNonce) {
      throw new InvariantViolationError("nonce " + nonce + " not consumable for " + account);
    }
    m.lastNonce = nonce;
    dirt.meta(account);
  }

  /**
   * Credits {@code amount} of {@code asset} to {@code account}'s free balance. Asset existence is
   * checked by the caller.
   */
  public void deposit(long account, int asset, long amount) {
    if (amount <= 0) {
      throw new InvalidOrderException("deposit must be positive");
    }
    if (amount > Limits.MAX_DEPOSIT) {
      throw new LimitExceededException("deposit exceeds MAX_DEPOSIT");
    }
    if (!meta.containsKey(account)) {
      throw new InvalidOrderException("deposit target not registered: " + account);
    }
    long newSupply = Math.addExact(supply(asset), amount); // both < 2^62: cannot overflow
    if (newSupply > MAX_SUPPLY) {
      throw new LimitExceededException("asset supply would exceed limit");
    }
    supply.put(asset, newSupply);
    move(account, asset, amount, 0);
  }

  // ------------------------------------------------------------------ escrow

  /**
   * Escrows what {@code o} needs: a buy needs {@code limitPrice * remaining} quote, a sell needs
   * {@code remaining} base. No-op for orders that do not escrow upfront.
   *
   * @throws InsufficientBalanceException before any mutation
   */
  public void reserve(Order o, Instrument in) {
    if (!o.escrowsUpfront()) {
      return;
    }
    int asset = escrowAsset(o, in);
    long need = escrowAmount(o);
    if (free(o.accountId(), asset) < need) {
      throw new InsufficientBalanceException(
          "need " + need + " of asset " + asset + ", have " + free(o.accountId(), asset));
    }
    move(o.accountId(), asset, -need, need);
  }

  /** Returns the escrow of {@code o}'s unfilled remainder to free balance. */
  public void release(Order o, Instrument in) {
    if (!o.escrowsUpfront()) {
      return;
    }
    long a = escrowAmount(o);
    if (a > 0) {
      move(o.accountId(), escrowAsset(o, in), a, -a);
    }
  }

  static int escrowAsset(Order o, Instrument in) {
    return o.side() == Side.BUY ? in.quote() : in.base();
  }

  static long escrowAmount(Order o) {
    return o.side() == Side.BUY ? Math.multiplyExact(o.limitPrice(), o.remaining()) : o.remaining();
  }

  // ------------------------------------------------------------------ settlement

  /**
   * Settles one fill (§6.1). Each side pays its fee in the asset it receives, rounded down; fees go
   * to the fee account. A limit buyer's escrow at {@code buyEscrowPrice} is released, and any price
   * improvement is refunded to free.
   */
  public void settle(Trade t, Instrument in) {
    long cost = Math.multiplyExact(t.price(), t.qty());
    boolean buyerIsTaker = t.takerSide() == Side.BUY;
    long baseFee = bps(t.qty(), buyerIsTaker ? in.takerBps() : in.makerBps());
    long quoteFee = bps(cost, buyerIsTaker ? in.makerBps() : in.takerBps());
    if (t.buyEscrowPrice() == 0) {
      move(t.buyAccount(), in.quote(), -cost, 0); // market buy pays from free
    } else {
      long esc = Math.multiplyExact(t.buyEscrowPrice(), t.qty());
      move(t.buyAccount(), in.quote(), Math.subtractExact(esc, cost), -esc);
    }
    move(t.sellAccount(), in.base(), 0, -t.qty());
    move(t.buyAccount(), in.base(), Math.subtractExact(t.qty(), baseFee), 0);
    move(t.sellAccount(), in.quote(), Math.subtractExact(cost, quoteFee), 0);
    move(Accounts.FEE_ACCOUNT, in.base(), baseFee, 0);
    move(Accounts.FEE_ACCOUNT, in.quote(), quoteFee, 0);
  }

  /** {@code floor(x * b / 10_000)} for non-negative inputs. */
  public static long bps(long x, int b) {
    return Math.multiplyExact(x, b) / 10_000;
  }

  private void move(long account, int asset, long dFree, long dHeld) {
    if (dFree == 0 && dHeld == 0) {
      return;
    }
    long k = key(account, asset);
    dirt.ledger(k);
    Bal b = bal.get(k);
    long free;
    long held;
    try {
      free = Math.addExact(b == null ? 0 : b.free, dFree);
      held = Math.addExact(b == null ? 0 : b.held, dHeld);
    } catch (ArithmeticException e) {
      throw new InvariantViolationError("balance overflow for " + account + "/" + asset);
    }
    if (free < 0 || held < 0) {
      throw new InvariantViolationError(
          "negative balance for " + account + "/" + asset + ": " + free + "/" + held);
    }
    if (free == 0 && held == 0) {
      bal.remove(k);
    } else if (b == null) {
      bal.put(k, new Bal(free, held));
    } else {
      b.free = free;
      b.held = held;
    }
  }

  // ------------------------------------------------------------------ snapshot support

  void restoreBalance(long account, int asset, long free, long held) {
    if (free < 0 || held < 0 || (free == 0 && held == 0)) {
      throw new IllegalArgumentException("bad balance entry");
    }
    dirt.ledger(key(account, asset));
    if (bal.putIfAbsent(key(account, asset), new Bal(free, held)) != null) {
      throw new IllegalArgumentException("duplicate balance entry");
    }
    supply.merge(asset, Math.addExact(free, held), Math::addExact);
  }

  void restoreAccount(long account, byte[] pubKey, long lastNonce) {
    dirt.meta(account);
    if (meta.putIfAbsent(account, new Meta(pubKey.clone(), lastNonce)) != null) {
      throw new IllegalArgumentException("duplicate account");
    }
  }

  public Ledger copy() {
    Ledger c = new Ledger();
    for (Map.Entry<Long, Bal> e : bal.entrySet()) {
      c.bal.put(e.getKey(), new Bal(e.getValue().free, e.getValue().held));
    }
    for (Map.Entry<Long, Meta> e : meta.entrySet()) {
      c.meta.put(e.getKey(), new Meta(e.getValue().pubKey, e.getValue().lastNonce));
    }
    c.supply.putAll(supply);
    return c;
  }
}
