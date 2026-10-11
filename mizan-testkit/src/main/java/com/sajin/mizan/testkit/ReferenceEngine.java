package com.sajin.mizan.testkit;

import com.sajin.mizan.core.Cancel;
import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Instrument;
import com.sajin.mizan.core.Limits;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.crypto.SignatureVerifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A deliberately naive, independent implementation of the engine semantics (§6), used as the oracle
 * for differential testing. It shares <b>no</b> code with {@code OrderBook} or {@code Ledger}: the
 * book is a flat list re-filtered and re-sorted for every match step, and balances are a plain map.
 * It is slow on purpose; clarity is the point.
 *
 * <p>{@link Bug} injects a known defect, so tests can prove the differential harness catches it.
 */
public final class ReferenceEngine {
  private static final long FEE_ACCOUNT = 0;
  private static final long ADMIN = 1;
  private static final long MAX_SUPPLY = 1L << 62;

  /** Deliberate defects for mutation-checking the harness. */
  public enum Bug {
    NONE,
    NO_TAKER_FEE,
    LIFO_WITHIN_LEVEL,
    FOK_COUNTS_OWN_ORDERS,
    NO_PRICE_IMPROVEMENT_REFUND
  }

  private static final class Acct {
    final byte[] pk;
    long lastNonce;

    Acct(byte[] pk, long lastNonce) {
      this.pk = pk;
      this.lastNonce = lastNonce;
    }
  }

  private static final class Resting {
    final long id;
    final long account;
    final int instrument;
    final Side side;
    final long price;
    long rem;

    Resting(long id, long account, int instrument, Side side, long price, long rem) {
      this.id = id;
      this.account = account;
      this.instrument = instrument;
      this.side = side;
      this.price = price;
      this.rem = rem;
    }
  }

  /** Thrown internally for a reject; never escapes. */
  private static final class Reject extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Reject(String m) {
      super(m, null, false, false);
    }
  }

  private final GenesisConfig g;
  private final SignatureVerifier verifier;
  private final Bug bug;
  private final Map<Long, Acct> accounts = new HashMap<>();
  private final Map<Long, Map<Integer, long[]>> balances = new HashMap<>();
  private final Map<Integer, Long> supply = new HashMap<>();
  private final List<Resting> book = new ArrayList<>();
  private long nextOrderId = 1;
  private long nextTradeSeq = 1;

  public ReferenceEngine(GenesisConfig g, SignatureVerifier verifier) {
    this(g, verifier, Bug.NONE);
  }

  public ReferenceEngine(GenesisConfig g, SignatureVerifier verifier, Bug bug) {
    this.g = g;
    this.verifier = verifier;
    this.bug = bug;
    accounts.put(ADMIN, new Acct(g.adminPubKey().clone(), 0));
  }

  // ------------------------------------------------------------------ helpers

  private long[] bal(long account, int asset) {
    return balances
        .computeIfAbsent(account, k -> new HashMap<>())
        .computeIfAbsent(asset, k -> new long[2]);
  }

  private long free(long account, int asset) {
    return bal(account, asset)[0];
  }

  private Instrument instrument(int id) {
    for (Instrument i : g.instruments()) {
      if (i.id() == id) {
        return i;
      }
    }
    return null;
  }

  private boolean assetExists(int asset) {
    for (GenesisConfig.Asset a : g.assets()) {
      if (a.id() == asset) {
        return true;
      }
    }
    return false;
  }

  private static boolean crosses(Side takerSide, long takerPrice, long restingPrice) {
    return takerSide == Side.BUY ? restingPrice <= takerPrice : restingPrice >= takerPrice;
  }

  private long aggregate(int instrument, Side side, long price) {
    long sum = 0;
    for (Resting r : book) {
      if (r.instrument == instrument && r.side == side && r.price == price) {
        sum += r.rem;
      }
    }
    return sum;
  }

  /** Opposite-side crossing orders in priority order: best price, then arrival. */
  private List<Resting> candidates(int instrument, Side takerSide, long takerPrice) {
    List<Resting> c = new ArrayList<>();
    for (Resting r : book) {
      if (r.instrument == instrument
          && r.side != takerSide
          && crosses(takerSide, takerPrice, r.price)) {
        c.add(r);
      }
    }
    Comparator<Resting> byPrice =
        takerSide == Side.BUY
            ? Comparator.comparingLong((Resting r) -> r.price)
            : Comparator.comparingLong((Resting r) -> -r.price);
    Comparator<Resting> byTime =
        bug == Bug.LIFO_WITHIN_LEVEL
            ? Comparator.comparingLong((Resting r) -> -r.id)
            : Comparator.comparingLong((Resting r) -> r.id);
    c.sort(byPrice.thenComparing(byTime));
    return c;
  }

  private void releaseResting(Resting r, Instrument in) {
    if (r.side == Side.BUY) {
      long a = r.price * r.rem;
      bal(r.account, in.quote())[1] -= a;
      bal(r.account, in.quote())[0] += a;
    } else {
      bal(r.account, in.base())[1] -= r.rem;
      bal(r.account, in.base())[0] += r.rem;
    }
  }

  // ------------------------------------------------------------------ execute

  public ExecResult execute(SignedCommand sc, EngineListener l) {
    long ref =
        switch (sc.body()) {
          case Place p -> p.clientOrderId();
          case Cancel c -> c.orderId();
          case Deposit d -> d.target();
          case Register r -> 0;
        };
    String authError = authError(sc);
    if (authError != null) {
      l.onReject(sc.accountId(), ref, authError);
      return ExecResult.authFailed(authError);
    }
    if (sc.body() instanceof Register r) {
      accounts.put(sc.accountId(), new Acct(r.pubKey().clone(), sc.nonce()));
      return ExecResult.accepted(0);
    }
    accounts.get(sc.accountId()).lastNonce = sc.nonce();
    try {
      long id =
          switch (sc.body()) {
            case Deposit d -> deposit(d);
            case Cancel c -> cancel(sc.accountId(), c, l);
            case Place p -> place(sc.accountId(), p, l);
            case Register r -> throw new IllegalStateException();
          };
      return ExecResult.accepted(id);
    } catch (Reject e) {
      l.onReject(sc.accountId(), ref, e.getMessage());
      return ExecResult.rejected(e.getMessage());
    }
  }

  private String authError(SignedCommand sc) {
    if (sc.chainId() != g.chainId()) {
      return "chain";
    }
    byte[] key;
    if (sc.body() instanceof Register r) {
      if (sc.accountId() < 1 || sc.accountId() > Limits.MAX_ACCOUNT) {
        return "account range";
      }
      if (accounts.containsKey(sc.accountId())) {
        return "registered";
      }
      if (sc.nonce() < 1) {
        return "nonce";
      }
      key = r.pubKey();
    } else {
      Acct a = accounts.get(sc.accountId());
      if (a == null) {
        return "unknown account";
      }
      if (sc.nonce() <= a.lastNonce) {
        return "stale nonce";
      }
      if (sc.body() instanceof Deposit && sc.accountId() != ADMIN) {
        return "not admin";
      }
      key = a.pk;
    }
    if (!verifier.verify(key, CommandCodec.unsignedBytes(sc), sc.signature())) {
      return "signature";
    }
    return null;
  }

  private long deposit(Deposit d) {
    if (!assetExists(d.asset())) {
      throw new Reject("asset");
    }
    if (d.amount() <= 0 || d.amount() > Limits.MAX_DEPOSIT) {
      throw new Reject("amount");
    }
    if (!accounts.containsKey(d.target())) {
      throw new Reject("target");
    }
    long s = supply.getOrDefault(d.asset(), 0L);
    if (s + d.amount() > MAX_SUPPLY) {
      throw new Reject("supply");
    }
    supply.put(d.asset(), s + d.amount());
    bal(d.target(), d.asset())[0] += d.amount();
    return 0;
  }

  private long cancel(long account, Cancel c, EngineListener l) {
    Instrument in = instrument(c.instrument());
    if (in == null) {
      throw new Reject("instrument");
    }
    Resting found = null;
    for (Resting r : book) {
      if (r.id == c.orderId() && r.instrument == c.instrument()) {
        found = r;
      }
    }
    if (found == null || found.account != account) {
      throw new Reject("no such order");
    }
    book.remove(found);
    releaseResting(found, in);
    l.onCancelled(found.id, "USER");
    l.onBookDelta(in.id(), found.side, found.price, aggregate(in.id(), found.side, found.price));
    return found.id;
  }

  private long place(long account, Place p, EngineListener l) {
    Instrument in = instrument(p.instrument());
    if (in == null) {
      throw new Reject("instrument");
    }
    if (p.price() <= 0 || p.qty() <= 0) {
      throw new Reject("non-positive");
    }
    if (p.price() > Math.min(Limits.MAX_PRICE, in.maxPrice())
        || p.qty() > Math.min(Limits.MAX_QTY, in.maxQty())) {
      throw new Reject("cap");
    }
    if (p.price() % in.tick() != 0 || p.qty() % in.lot() != 0) {
      throw new Reject("tick/lot");
    }
    boolean market = p.type() == OrderType.MARKET;
    if (market && p.tif() != TimeInForce.IOC) {
      throw new Reject("market must be IOC");
    }
    if (p.tif() == TimeInForce.FOK && market) {
      throw new Reject("FOK must be limit");
    }
    Side side = p.side();
    if (p.tif() == TimeInForce.FOK) {
      long available = 0;
      for (Resting r : candidates(in.id(), side, p.price())) {
        if (r.account != account || bug == Bug.FOK_COUNTS_OWN_ORDERS) {
          available += r.rem;
        }
      }
      if (available < p.qty()) {
        throw new Reject("FOK");
      }
    }
    if (p.tif() == TimeInForce.POST_ONLY && !candidates(in.id(), side, p.price()).isEmpty()) {
      throw new Reject("POST_ONLY");
    }
    boolean escrowed = !market || side == Side.SELL;
    if (escrowed) {
      int asset = side == Side.BUY ? in.quote() : in.base();
      long need = side == Side.BUY ? p.price() * p.qty() : p.qty();
      if (free(account, asset) < need) {
        throw new Reject("balance");
      }
      bal(account, asset)[0] -= need;
      bal(account, asset)[1] += need;
    }

    long id = nextOrderId++;
    l.onAccepted(account, p.clientOrderId(), id);
    long rem = p.qty();
    while (rem > 0) {
      List<Resting> c = candidates(in.id(), side, p.price());
      if (c.isEmpty()) {
        break;
      }
      Resting m = c.get(0);
      if (m.account == account) {
        book.remove(m);
        releaseResting(m, in);
        l.onCancelled(m.id, "STP");
        l.onBookDelta(in.id(), m.side, m.price, aggregate(in.id(), m.side, m.price));
        continue;
      }
      long q = Math.min(m.rem, rem);
      if (market && side == Side.BUY) {
        q = Math.min(q, free(account, in.quote()) / m.price);
      }
      if (q <= 0) {
        break;
      }
      m.rem -= q;
      rem -= q;
      long buyer = side == Side.BUY ? account : m.account;
      long seller = side == Side.BUY ? m.account : account;
      long escrowPx;
      if (side == Side.BUY) {
        escrowPx = market ? 0 : p.price();
      } else {
        escrowPx = m.price;
      }
      Trade t =
          new Trade(nextTradeSeq++, in.id(), m.id, id, buyer, seller, side, m.price, q, escrowPx);
      settle(t, in);
      l.onTrade(t);
      if (m.rem == 0) {
        book.remove(m);
      }
      l.onBookDelta(in.id(), m.side, m.price, aggregate(in.id(), m.side, m.price));
    }
    if (rem > 0) {
      boolean rests = !market && (p.tif() == TimeInForce.GTC || p.tif() == TimeInForce.POST_ONLY);
      if (rests) {
        book.add(new Resting(id, account, in.id(), side, p.price(), rem));
        l.onBookDelta(in.id(), side, p.price(), aggregate(in.id(), side, p.price()));
      } else if (escrowed) {
        if (side == Side.BUY) {
          bal(account, in.quote())[1] -= p.price() * rem;
          bal(account, in.quote())[0] += p.price() * rem;
        } else {
          bal(account, in.base())[1] -= rem;
          bal(account, in.base())[0] += rem;
        }
      }
    }
    return id;
  }

  private void settle(Trade t, Instrument in) {
    long cost = t.price() * t.qty();
    boolean buyerIsTaker = t.takerSide() == Side.BUY;
    int takerBps = bug == Bug.NO_TAKER_FEE ? 0 : in.takerBps();
    long baseFee = t.qty() * (buyerIsTaker ? takerBps : in.makerBps()) / 10_000;
    long quoteFee = cost * (buyerIsTaker ? in.makerBps() : takerBps) / 10_000;
    long[] buyerQuote = bal(t.buyAccount(), in.quote());
    if (t.buyEscrowPrice() == 0) {
      buyerQuote[0] -= cost;
    } else {
      long escrow = t.buyEscrowPrice() * t.qty();
      buyerQuote[1] -= escrow;
      if (bug != Bug.NO_PRICE_IMPROVEMENT_REFUND) {
        buyerQuote[0] += escrow - cost;
      }
    }
    bal(t.sellAccount(), in.base())[1] -= t.qty();
    bal(t.buyAccount(), in.base())[0] += t.qty() - baseFee;
    bal(t.sellAccount(), in.quote())[0] += cost - quoteFee;
    bal(FEE_ACCOUNT, in.base())[0] += baseFee;
    bal(FEE_ACCOUNT, in.quote())[0] += quoteFee;
  }

  // ------------------------------------------------------------------ dump

  public StateDump dump() {
    List<StateDump.Balance> bs = new ArrayList<>();
    balances.forEach(
        (account, assets) ->
            assets.forEach(
                (asset, v) -> {
                  if (v[0] != 0 || v[1] != 0) {
                    bs.add(new StateDump.Balance(account, asset, v[0], v[1]));
                  }
                }));
    bs.sort(
        Comparator.comparingLong(StateDump.Balance::account)
            .thenComparingInt(StateDump.Balance::asset));
    List<StateDump.Account> as = new ArrayList<>();
    accounts.forEach(
        (id, a) -> as.add(new StateDump.Account(id, a.lastNonce, HexFormat.of().formatHex(a.pk))));
    as.sort(Comparator.comparingLong(StateDump.Account::account));
    List<StateDump.Resting> rs = new ArrayList<>();
    for (Resting r : book) {
      rs.add(new StateDump.Resting(r.instrument, r.side, r.price, r.id, r.account, r.rem));
    }
    rs.sort(
        Comparator.comparingInt(StateDump.Resting::instrument)
            .thenComparing(StateDump.Resting::side)
            .thenComparingLong(r -> r.side() == Side.BUY ? -r.price() : r.price())
            .thenComparingLong(StateDump.Resting::id));
    return new StateDump(nextOrderId, nextTradeSeq, bs, as, rs);
  }
}
