package com.sajin.mizan.engine;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Cancel;
import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Instrument;
import com.sajin.mizan.core.InvalidOrderException;
import com.sajin.mizan.core.InvalidSignatureException;
import com.sajin.mizan.core.LimitExceededException;
import com.sajin.mizan.core.Limits;
import com.sajin.mizan.core.OrderCommand;
import com.sajin.mizan.core.OrderRejectedException;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.StaleNonceException;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.crypto.SignatureVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The deterministic state machine: ledger, books and counters (§6.3). Not thread-safe: exactly one
 * thread owns each instance (ADR 0001).
 *
 * <p>{@link #execute} runs three phases:
 *
 * <ol>
 *   <li><b>authenticate</b>: on failure the result is {@code AUTH_FAILED} and nothing changes;
 *   <li><b>burn the nonce</b>;
 *   <li><b>business logic</b>: either fully applied ({@code ACCEPTED}) or {@code REJECTED} with no
 *       change beyond the burned nonce.
 * </ol>
 */
public final class ExecutionCore {
  /** A listener that ignores every event. */
  public static final EngineListener SILENT = t -> {};

  private final GenesisConfig genesis;
  private final SignatureVerifier verifier;
  private final boolean verifySignatures;
  private final TreeMap<Integer, Instrument> instruments;
  private final TreeSet<Integer> assets;
  private final Ledger ledger;
  private final TreeMap<Integer, OrderBook> books;
  private long nextOrderId;
  private long nextTradeSeq;

  private ExecutionCore(
      GenesisConfig genesis,
      SignatureVerifier verifier,
      boolean verifySignatures,
      Ledger ledger,
      Map<Integer, OrderBook> books,
      long nextOrderId,
      long nextTradeSeq) {
    this.genesis = genesis;
    this.verifier = verifier;
    this.verifySignatures = verifySignatures;
    this.instruments = new TreeMap<>();
    for (Instrument i : genesis.instruments()) {
      instruments.put(i.id(), i);
    }
    this.assets = new TreeSet<>();
    for (GenesisConfig.Asset a : genesis.assets()) {
      assets.add(a.id());
    }
    this.ledger = ledger;
    this.books = new TreeMap<>(books);
    this.nextOrderId = nextOrderId;
    this.nextTradeSeq = nextTradeSeq;
  }

  /**
   * The state at height 0 (§5.3, M2-T6): the fee account (0) exists implicitly with no key and can
   * never sign; the admin account (1) is registered with the genesis admin key; each instrument
   * gets an empty book.
   */
  public static ExecutionCore genesis(GenesisConfig g, SignatureVerifier verifier) {
    return genesis(g, verifier, true);
  }

  /**
   * As {@link #genesis(GenesisConfig, SignatureVerifier)}; {@code verifySignatures = false} is for
   * benchmarks only.
   */
  public static ExecutionCore genesis(
      GenesisConfig g, SignatureVerifier verifier, boolean verifySignatures) {
    if (g.stateRootVersion() != 1) {
      throw new IllegalArgumentException(
          "stateRootVersion " + g.stateRootVersion() + " is not supported yet");
    }
    Ledger ledger = new Ledger();
    ledger.register(Accounts.ADMIN_ACCOUNT, g.adminPubKey(), 0);
    TreeMap<Integer, OrderBook> books = new TreeMap<>();
    for (Instrument i : g.instruments()) {
      books.put(i.id(), new OrderBook(i.id()));
    }
    return new ExecutionCore(g, verifier, verifySignatures, ledger, books, 1, 1);
  }

  /** Rebuilds a core from decoded snapshot parts (used by {@link StateCodec}). */
  static ExecutionCore restore(
      GenesisConfig g,
      SignatureVerifier verifier,
      Ledger ledger,
      Map<Integer, OrderBook> books,
      long nextOrderId,
      long nextTradeSeq) {
    return new ExecutionCore(g, verifier, true, ledger, books, nextOrderId, nextTradeSeq);
  }

  /** Deep, independent copy of all state. */
  public ExecutionCore copy() {
    TreeMap<Integer, OrderBook> b = new TreeMap<>();
    for (Map.Entry<Integer, OrderBook> e : books.entrySet()) {
      b.put(e.getKey(), e.getValue().copy());
    }
    return new ExecutionCore(
        genesis, verifier, verifySignatures, ledger.copy(), b, nextOrderId, nextTradeSeq);
  }

  // ------------------------------------------------------------------ execute

  public ExecResult execute(SignedCommand sc, EngineListener l) {
    Objects.requireNonNull(l, "listener");
    try {
      authenticate(sc);
    } catch (OrderRejectedException e) {
      l.onReject(sc.accountId(), refOf(sc.body()), e.getMessage());
      return ExecResult.authFailed(e.getMessage());
    }
    if (!(sc.body() instanceof Register)) {
      ledger.consumeNonce(sc.accountId(), sc.nonce());
    }
    try {
      long id =
          switch (sc.body()) {
            case Register r -> {
              ledger.register(sc.accountId(), r.pubKey(), sc.nonce());
              yield 0L;
            }
            case Deposit d -> deposit(d);
            case Cancel c -> cancel(sc.accountId(), c, l);
            case Place p -> place(sc.accountId(), p, l);
          };
      return ExecResult.accepted(id);
    } catch (OrderRejectedException e) {
      l.onReject(sc.accountId(), refOf(sc.body()), e.getMessage());
      return ExecResult.rejected(e.getMessage());
    }
  }

  private void authenticate(SignedCommand sc) {
    if (sc.chainId() != genesis.chainId()) {
      throw new InvalidSignatureException("wrong chain id " + sc.chainId());
    }
    long account = sc.accountId();
    byte[] key;
    if (sc.body() instanceof Register r) {
      if (account <= 0 || account > Limits.MAX_ACCOUNT) {
        throw new InvalidSignatureException("account id out of range: " + account);
      }
      if (ledger.isRegistered(account)) {
        throw new InvalidSignatureException("account already registered: " + account);
      }
      if (sc.nonce() <= 0) {
        throw new StaleNonceException("nonce must be positive");
      }
      key = r.pubKey(); // self-certifying: the body's key must have signed the command
    } else {
      key = ledger.pubKey(account);
      if (key == null) {
        throw new InvalidSignatureException("unknown account " + account);
      }
      if (sc.nonce() <= ledger.lastNonce(account)) {
        throw new StaleNonceException(
            "nonce " + sc.nonce() + " <= last " + ledger.lastNonce(account));
      }
      if (sc.body() instanceof Deposit && account != Accounts.ADMIN_ACCOUNT) {
        throw new InvalidSignatureException("only the admin account may deposit");
      }
    }
    if (verifySignatures && !verifier.verify(key, CommandCodec.unsignedBytes(sc), sc.signature())) {
      throw new InvalidSignatureException("bad signature");
    }
  }

  private static long refOf(OrderCommand body) {
    return switch (body) {
      case Place p -> p.clientOrderId();
      case Cancel c -> c.orderId();
      case Deposit d -> d.target();
      case Register r -> 0;
    };
  }

  private long deposit(Deposit d) {
    if (!assets.contains(d.asset())) {
      throw new InvalidOrderException("unknown asset " + d.asset());
    }
    ledger.deposit(d.target(), d.asset(), d.amount());
    return 0;
  }

  private long cancel(long account, Cancel c, EngineListener l) {
    OrderBook book = books.get(c.instrument());
    if (book == null) {
      throw new InvalidOrderException("unknown instrument " + c.instrument());
    }
    Order o = book.cancel(c.orderId(), account);
    ledger.release(o, instruments.get(c.instrument()));
    l.onCancelled(o.id(), "USER");
    l.onBookDelta(
        c.instrument(), o.side(), o.limitPrice(), book.levelTotal(o.side(), o.limitPrice()));
    return o.id();
  }

  private long place(long account, Place p, EngineListener l) {
    Instrument in = instruments.get(p.instrument());
    if (in == null) {
      throw new InvalidOrderException("unknown instrument " + p.instrument());
    }
    validate(p, in);
    OrderBook book = books.get(p.instrument());
    long id = nextOrderId; // consumed only once the order is accepted (ADR 0008)
    Order order = Order.of(id, account, p);
    if (p.tif() == TimeInForce.FOK && book.availableQty(order) < order.remaining()) {
      throw new InvalidOrderException("FOK order cannot be filled completely");
    }
    if (p.tif() == TimeInForce.POST_ONLY && book.wouldCross(order)) {
      throw new InvalidOrderException("POST_ONLY order would cross");
    }
    ledger.reserve(order, in);
    // ---- accepted: nothing below may reject ----
    nextOrderId++;
    l.onAccepted(account, p.clientOrderId(), id);
    FillGuard guard =
        order.type() == OrderType.MARKET && order.side() == Side.BUY
            ? (taker, px) -> ledger.free(taker.accountId(), in.quote()) / px
            : FillGuard.UNLIMITED;
    nextTradeSeq = book.match(order, nextTradeSeq, guard, new Sink(in, l));
    if (order.remaining() > 0) {
      if (order.rests()) {
        book.rest(order);
        l.onBookDelta(
            in.id(),
            order.side(),
            order.limitPrice(),
            book.levelTotal(order.side(), order.limitPrice()));
      } else {
        ledger.release(order, in);
      }
    }
    return id;
  }

  /** Static validation of a {@code Place}; throws before any mutation. */
  private static void validate(Place p, Instrument in) {
    if (p.price() <= 0 || p.qty() <= 0) {
      throw new InvalidOrderException("price and qty must be positive");
    }
    if (p.price() > in.priceCap()) {
      throw new LimitExceededException("price above cap " + in.priceCap());
    }
    if (p.qty() > in.qtyCap()) {
      throw new LimitExceededException("qty above cap " + in.qtyCap());
    }
    if (p.price() % in.tick() != 0) {
      throw new InvalidOrderException("price not a multiple of tick " + in.tick());
    }
    if (p.qty() % in.lot() != 0) {
      throw new InvalidOrderException("qty not a multiple of lot " + in.lot());
    }
    if (p.type() == OrderType.MARKET && p.tif() != TimeInForce.IOC) {
      throw new InvalidOrderException("market orders must be IOC");
    }
    if (p.tif() == TimeInForce.FOK && p.type() != OrderType.LIMIT) {
      throw new InvalidOrderException("FOK is only allowed on limit orders");
    }
  }

  /** Settles fills and forwards book events for one {@code place}. */
  private final class Sink implements MatchSink {
    private final Instrument in;
    private final EngineListener l;

    Sink(Instrument in, EngineListener l) {
      this.in = in;
      this.l = l;
    }

    @Override
    public void onFill(Trade t) {
      ledger.settle(t, in);
      l.onTrade(t);
    }

    @Override
    public void onSelfTradeCancel(Order maker) {
      ledger.release(maker, in);
      l.onCancelled(maker.id(), "STP");
    }

    @Override
    public void onDelta(Side side, long price, long aggQty) {
      l.onBookDelta(in.id(), side, price, aggQty);
    }
  }

  // ------------------------------------------------------------------ views

  public byte[] stateRoot() {
    return SimpleStateRoot.compute(this);
  }

  public long nextTradeSeq() {
    return nextTradeSeq;
  }

  public long nextOrderId() {
    return nextOrderId;
  }

  public GenesisConfig genesisConfig() {
    return genesis;
  }

  public long free(long account, int asset) {
    return ledger.free(account, asset);
  }

  public long held(long account, int asset) {
    return ledger.held(account, asset);
  }

  public boolean isRegistered(long account) {
    return ledger.isRegistered(account);
  }

  public long lastNonce(long account) {
    return ledger.lastNonce(account);
  }

  /** Sum of free + held of {@code asset} over all accounts, including the fee account. */
  public long ledgerTotal(int asset) {
    return ledger.total(asset);
  }

  public void forEachBalance(Ledger.BalanceVisitor v) {
    ledger.forEachBalance(v);
  }

  public void forEachAccount(Ledger.AccountVisitor v) {
    ledger.forEachAccount((account, nonce, pk) -> v.visit(account, nonce, pk.clone()));
  }

  public List<Integer> instrumentIds() {
    return List.copyOf(books.keySet());
  }

  /** Resting orders of one side in priority order: best price first, FIFO within a level. */
  public List<RestingOrder> restingOrders(int instrument, Side side) {
    List<RestingOrder> out = new ArrayList<>();
    for (PriceLevel lvl : book(instrument).levels(side)) {
      for (Order o : lvl.orders()) {
        out.add(RestingOrder.of(o));
      }
    }
    return out;
  }

  public List<OrderBook.Level> depth(int instrument, Side side, int n) {
    return book(instrument).depth(side, n);
  }

  OrderBook book(int instrument) {
    OrderBook b = books.get(instrument);
    if (b == null) {
      throw new IllegalArgumentException("unknown instrument " + instrument);
    }
    return b;
  }

  Iterable<OrderBook> books() {
    return books.values();
  }

  Ledger ledger() {
    return ledger;
  }

  SignatureVerifier verifier() {
    return verifier;
  }
}
