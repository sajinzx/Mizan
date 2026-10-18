package com.sajin.mizan.node;

import com.sajin.mizan.chain.bft.RoundBft;
import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Cancel;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Instrument;
import com.sajin.mizan.core.Limits;
import com.sajin.mizan.core.OrderCommand;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.crypto.Signer;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.sim.MizanClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A complete Mizan network in one process, for exploring it (the dashboard's sandbox mode):
 *
 * <ul>
 *   <li>{@code n} rotating-proposer validators over real localhost TCP, each with its own block
 *       log, snapshots and consensus WAL, and state root v2 (sparse Merkle trees);
 *   <li>{@link MarketBots} providing liquidity and flow on two instruments;
 *   <li>a funded account for the person at the controls ({@link #YOU});
 *   <li>a {@link ShadowReplica} that re-executes every committed block, so the dashboard can show
 *       market data and prove each validator's state root against an independent replay;
 *   <li>fault injection: stop and restart a validator (recovery from its data directory), isolate
 *       one, partition the network 2|2, heal; and {@code verify-chain} on every data directory.
 * </ul>
 *
 * <p>Simulated assets only; the dev keys are public.
 */
public final class Sandbox implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(Sandbox.class);

  /** The account of the person using the sandbox. */
  public static final long YOU = 9_000;

  public static final int MZN = 1;
  public static final int USD = 2;
  public static final int ORB = 3;
  public static final int MZN_USD = 1;
  public static final int ORB_USD = 2;

  private static final int KEEP_EVENTS = 300;
  private static final int KEEP_ORDERS = 200;

  private final GenesisConfig genesis;
  private final Path dataDir;
  private final int n;
  private final int[] gatewayPorts;
  private final ValidatorNode[] nodes;
  private final boolean[] isolatedFlags;
  private final List<Set<Integer>> blocked = new ArrayList<>();
  private final long[] restarts;
  private final long[] recoveryMillis;
  private final AtomicLongArray roundBumps;
  private final AtomicLongArray equivocations;
  private final AtomicLongArray revokes;
  private final AtomicLongArray decided;
  private final AtomicLong syncedBlocks = new AtomicLong();
  private final ShadowReplica shadow;
  private final MarketBots bots;
  private final Map<Integer, String> symbols = new TreeMap<>();
  private final ArrayDeque<SandboxView.Event> events = new ArrayDeque<>();
  private final List<Tracked> myOrders = new CopyOnWriteArrayList<>();
  private final AtomicLong myNonce = new AtomicLong(1);
  private final Map<Integer, MizanClient> myClients = new ConcurrentHashMap<>();
  private volatile boolean closed;

  /** One of your orders and what has happened to it so far. */
  private static final class Tracked {
    final long nonce;
    final String kind;
    final int instrument;
    final Side side;
    final String type;
    final String tif;
    final long price;
    final long qty;
    final int via;
    final long sent = System.currentTimeMillis();
    volatile String status = "SENT";
    volatile long orderId;
    volatile long height;
    volatile String reason = "";
    volatile long ackAt;
    volatile long finalAt;

    Tracked(
        long nonce,
        String kind,
        int instrument,
        Side side,
        String type,
        String tif,
        long price,
        long qty,
        int via) {
      this.nonce = nonce;
      this.kind = kind;
      this.instrument = instrument;
      this.side = side;
      this.type = type;
      this.tif = tif;
      this.price = price;
      this.qty = qty;
      this.via = via;
    }

    SandboxView.MyOrder view() {
      return new SandboxView.MyOrder(
          nonce,
          kind,
          instrument,
          side,
          type,
          tif,
          price,
          qty,
          status,
          orderId,
          height,
          reason,
          via,
          sent,
          ackAt == 0 ? -1 : ackAt - sent,
          finalAt == 0 ? -1 : finalAt - sent);
    }
  }

  private Sandbox(GenesisConfig g, Path dataDir) throws IOException {
    this.genesis = g;
    this.dataDir = dataDir;
    this.n = g.validators().size();
    this.gatewayPorts = new int[n];
    this.nodes = new ValidatorNode[n];
    this.isolatedFlags = new boolean[n];
    this.restarts = new long[n];
    this.recoveryMillis = new long[n];
    this.roundBumps = new AtomicLongArray(n);
    this.equivocations = new AtomicLongArray(n);
    this.revokes = new AtomicLongArray(n);
    this.decided = new AtomicLongArray(n);
    for (int i = 0; i < n; i++) {
      gatewayPorts[i] = freePort();
      blocked.add(ConcurrentHashMap.newKeySet());
    }
    symbols.put(MZN_USD, "MZN/USD");
    symbols.put(ORB_USD, "ORB/USD");
    this.shadow = new ShadowReplica(g, symbols);
    this.bots =
        new MarketBots(
            g.chainId(), Map.of(MZN_USD, 25_000L, ORB_USD, 1_800L), this::liveGatewayPorts);
  }

  /** The sandbox genesis: four validators on free localhost ports, state root v2, three assets. */
  static GenesisConfig genesis(int validators) throws IOException {
    GenesisConfig dev = DevGenesis.config(validators);
    List<GenesisConfig.Validator> vs = new ArrayList<>();
    for (GenesisConfig.Validator v : dev.validators()) {
      vs.add(new GenesisConfig.Validator(v.index(), v.pubKey(), "127.0.0.1", freePort()));
    }
    return new GenesisConfig(
        dev.chainId(),
        2,
        dev.adminPubKey(),
        List.of(
            new GenesisConfig.Asset(MZN, "MZN"),
            new GenesisConfig.Asset(USD, "USD"),
            new GenesisConfig.Asset(ORB, "ORB")),
        List.of(
            new Instrument(MZN_USD, MZN, USD, 1, 1, 2, 8, Limits.MAX_PRICE, Limits.MAX_QTY),
            new Instrument(ORB_USD, ORB, USD, 1, 1, 5, 15, Limits.MAX_PRICE, Limits.MAX_QTY)),
        vs,
        new GenesisConfig.BlockParams(2_000, 100, 1),
        dev.consensus());
  }

  /**
   * Starts {@code validators} validators in {@code dataDir} (created if missing), funds, trades.
   */
  public static Sandbox start(int validators, Path dataDir) throws IOException {
    Files.createDirectories(dataDir);
    Sandbox s = new Sandbox(genesis(validators), dataDir);
    GenesisJson.write(s.genesis, dataDir.resolve("genesis.json"));
    for (int i = 0; i < s.n; i++) {
      s.launch(i);
    }
    s.event(SandboxView.Severity.GOOD, "sandbox", s.n + " validators up; data in " + dataDir);
    try {
      s.fund();
    } catch (Exception e) {
      s.close();
      throw new IOException("funding the sandbox accounts failed", e);
    }
    s.bots.start();
    s.event(SandboxView.Severity.INFO, "bots", "market makers and takers started");
    return s;
  }

  private void launch(int i) throws IOException {
    long t0 = System.nanoTime();
    int me = i;
    FinalityListener finality = (h, cmds, results) -> shadow.offer(h, cmds);
    EngineListener committed =
        new EngineListener() {
          @Override
          public void onTrade(Trade t) {}

          @Override
          public void onRevoke(long fromTradeSeq) {
            revokes.incrementAndGet(me);
            event(
                SandboxView.Severity.WARN,
                "v" + me,
                "REVOKE from trade #"
                    + fromTradeSeq
                    + ": its own proposal lost, provisional results void");
          }
        };
    RoundBft.Observer observer =
        new RoundBft.Observer() {
          @Override
          public void roundStarted(long height, int round) {
            if (round > 0) {
              roundBumps.incrementAndGet(me);
              if (me == firstLive()) {
                event(
                    SandboxView.Severity.WARN,
                    "v" + me,
                    "round bump at height "
                        + height
                        + " → round "
                        + round
                        + ", proposer v"
                        + (height + round) % n);
              }
            }
          }

          @Override
          public void decided(long height, int round) {
            decided.incrementAndGet(me);
            shadow.decidedRound(height, round);
          }

          @Override
          public void equivocation(
              int validator, long height, int round, String kind, byte[] a, byte[] b) {
            equivocations.incrementAndGet(me);
            event(
                SandboxView.Severity.CRITICAL,
                "v" + me,
                "equivocation evidence: v"
                    + validator
                    + " "
                    + kind
                    + " at "
                    + height
                    + "/"
                    + round);
          }

          @Override
          public void synced(int peer, long height, int blocks) {
            syncedBlocks.addAndGet(blocks);
            event(
                SandboxView.Severity.INFO,
                "v" + me,
                "synced " + blocks + " blocks from v" + peer + ", now at height " + height);
          }

          @Override
          public void peerBanned(int peer, long untilMillis) {
            event(
                SandboxView.Severity.WARN,
                "v" + me,
                "banned v" + peer + " for an invalid sync block");
          }
        };
    nodes[i] =
        ValidatorNode.start(
            Node.Settings.dev(genesis, gatewayPorts[i]),
            Node.Persistence.at(dataDir.resolve("v" + i)).withSnapshotEvery(50),
            DevGenesis.validatorKey(i),
            i,
            new ValidatorNode.Hooks(List.of(committed), finality, observer));
    recoveryMillis[i] = (System.nanoTime() - t0) / 1_000_000;
    applyBlocks(i);
  }

  // ------------------------------------------------------------------ funding

  /** Registers and funds every bot and {@link #YOU}: pipelined, two blocks' worth of waiting. */
  private void fund() throws Exception {
    List<Long> accounts = new ArrayList<>(bots.accounts());
    accounts.add(YOU);
    try (MizanClient c = new MizanClient("127.0.0.1", gatewayPorts[0])) {
      List<SignedCommand> registers = new ArrayList<>();
      for (long a : accounts) {
        Signer key = DevGenesis.accountKey(a);
        registers.add(Tools.sign(key, genesis.chainId(), a, 1, new Register(key.publicKey())));
      }
      awaitAllFinal(c, registers);
      List<SignedCommand> deposits = new ArrayList<>();
      long admin = 0;
      for (long a : accounts) {
        long scale = a == YOU ? 1 : 4;
        deposits.add(admin(++admin, new Deposit(a, USD, 2_000_000_000L * scale)));
        deposits.add(admin(++admin, new Deposit(a, MZN, 200_000L * scale)));
        deposits.add(admin(++admin, new Deposit(a, ORB, 2_000_000L * scale)));
      }
      awaitAllFinal(c, deposits);
    }
    event(
        SandboxView.Severity.GOOD,
        "admin",
        "registered and funded " + accounts.size() + " accounts");
  }

  private static void awaitAllFinal(MizanClient c, List<SignedCommand> cmds) throws Exception {
    List<CompletableFuture<Messages.Final>> finals = new ArrayList<>();
    for (SignedCommand cmd : cmds) {
      finals.add(c.finality(cmd.accountId(), cmd.nonce()));
      var unused = c.send(cmd);
    }
    for (CompletableFuture<Messages.Final> f : finals) {
      f.get(60, TimeUnit.SECONDS);
    }
  }

  private SignedCommand admin(long nonce, OrderCommand body) throws Exception {
    return Tools.sign(
        DevGenesis.adminKey(), genesis.chainId(), Accounts.ADMIN_ACCOUNT, nonce, body);
  }

  // ------------------------------------------------------------------ your orders

  /** Sends one of your orders through validator {@code via}'s gateway; returns its nonce. */
  public long place(
      int via, int instrument, Side side, OrderType type, TimeInForce tif, long price, long qty) {
    Place p = new Place(myNonce.get() + 1, instrument, side, type, tif, price, qty);
    return submitMine(via, p, "ORDER", side, type.name(), tif.name(), price, qty, instrument);
  }

  /** Cancels one of your resting orders. */
  public long cancel(int via, long orderId, int instrument) {
    return submitMine(
        via, new Cancel(orderId, instrument), "CANCEL", null, "CANCEL", "-", 0, 0, instrument);
  }

  private synchronized long submitMine(
      int via,
      OrderCommand body,
      String kind,
      Side side,
      String type,
      String tif,
      long price,
      long qty,
      int instrument) {
    int v = nodes[Math.floorMod(via, n)] != null ? Math.floorMod(via, n) : firstLive();
    long nonce = myNonce.incrementAndGet();
    Tracked t = new Tracked(nonce, kind, instrument, side, type, tif, price, qty, v);
    myOrders.add(0, t);
    while (myOrders.size() > KEEP_ORDERS) {
      myOrders.remove(myOrders.size() - 1);
    }
    if (v < 0) {
      t.status = "NO VALIDATOR";
      return nonce;
    }
    try {
      MizanClient c = myClient(v);
      SignedCommand cmd =
          Tools.sign(DevGenesis.accountKey(YOU), genesis.chainId(), YOU, nonce, body);
      CompletableFuture<Messages.ServerMessage> ack = c.send(cmd);
      var unused =
          ack.whenComplete(
              (m, err) -> {
                if (err != null) {
                  t.status = "LOST";
                  t.reason = "connection to v" + v + " lost; retry";
                  myClients.remove(v);
                  return;
                }
                t.ackAt = System.currentTimeMillis();
                switch (m) {
                  case Messages.Accepted a -> {
                    t.status = "PROVISIONAL";
                    t.orderId = a.orderId();
                    awaitFinal(c, t);
                  }
                  case Messages.Final f -> finalize(t, f);
                  case Messages.Rejected r -> {
                    t.status = "REJECTED";
                    t.reason = r.message();
                    awaitFinal(c, t);
                  }
                  case Messages.Busy b -> {
                    t.status = "BUSY";
                    t.reason = "rate limited or queue full; retry";
                  }
                  default ->
                      t.status = m.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
                }
              });
    } catch (Exception e) {
      t.status = "ERROR";
      t.reason = e.toString();
    }
    return nonce;
  }

  private void awaitFinal(MizanClient c, Tracked t) {
    var unused =
        c.finality(YOU, t.nonce)
            .orTimeout(60, TimeUnit.SECONDS)
            .whenComplete(
                (f, err) -> {
                  if (err == null) {
                    finalize(t, f);
                  }
                });
  }

  private void finalize(Tracked t, Messages.Final f) {
    t.finalAt = System.currentTimeMillis();
    if (t.ackAt == 0) {
      t.ackAt = t.finalAt;
    }
    t.height = f.height();
    if (f.orderId() > 0) {
      t.orderId = f.orderId();
    }
    t.status = f.status() == Messages.Final.ACCEPTED ? "FINAL" : "FINAL REJECTED";
  }

  private MizanClient myClient(int v) throws IOException {
    MizanClient c = myClients.get(v);
    if (c == null) {
      c = new MizanClient("127.0.0.1", gatewayPorts[v]);
      myClients.put(v, c);
    }
    return c;
  }

  // ------------------------------------------------------------------ faults

  /** Stops validator {@code i} (its block log and WAL stay on disk). */
  public synchronized void stop(int i) {
    ValidatorNode v = nodes[i];
    if (v == null) {
      return;
    }
    nodes[i] = null;
    bots.forget(gatewayPorts[i]);
    MizanClient mine = myClients.remove(i);
    if (mine != null) {
      try {
        mine.close();
      } catch (IOException ignored) {
        // closing
      }
    }
    v.close();
    event(
        SandboxView.Severity.WARN,
        "v" + i,
        "stopped at height " + v.height() + "; the others carry on (f = 1)");
  }

  /** Restarts validator {@code i} from its data directory; it recovers, then catches up by sync. */
  public synchronized void restart(int i) throws IOException {
    if (nodes[i] != null) {
      return;
    }
    launch(i);
    restarts[i]++;
    event(
        SandboxView.Severity.GOOD,
        "v" + i,
        "restarted: recovered height "
            + nodes[i].height()
            + " from disk in "
            + recoveryMillis[i]
            + " ms; syncing");
  }

  /** Cuts validator {@code i} off from all others (or reconnects it). */
  public synchronized void isolate(int i, boolean on) {
    isolatedFlags[i] = on;
    for (int j = 0; j < n; j++) {
      if (j != i) {
        if (on) {
          blocked.get(i).add(j);
          blocked.get(j).add(i);
        } else if (!isolatedFlags[j]) {
          blocked.get(i).remove(j);
          blocked.get(j).remove(i);
        }
      }
    }
    applyBlocksAll();
    event(
        on ? SandboxView.Severity.WARN : SandboxView.Severity.GOOD,
        "v" + i,
        on ? "isolated from all peers" : "reconnected");
  }

  /**
   * Splits the validators into {@code left} and the rest: with n = 4 and 2|2 no side has a quorum.
   */
  public synchronized void partition(Set<Integer> left) {
    for (int i = 0; i < n; i++) {
      blocked.get(i).clear();
      isolatedFlags[i] = false;
      for (int j = 0; j < n; j++) {
        if (left.contains(i) != left.contains(j)) {
          blocked.get(i).add(j);
        }
      }
    }
    applyBlocksAll();
    event(
        SandboxView.Severity.WARN,
        "network",
        "partitioned " + left + " | rest: no side has 3 of 4, the chain halts safely");
  }

  /** Removes every partition and isolation. */
  public synchronized void heal() {
    for (int i = 0; i < n; i++) {
      blocked.get(i).clear();
      isolatedFlags[i] = false;
    }
    applyBlocksAll();
    event(SandboxView.Severity.GOOD, "network", "healed: all links restored");
  }

  /** Stops whichever validator proposes the next height at round 0. */
  public synchronized int stopNextProposer() {
    long h = maxHeight() + 1;
    int p = (int) (h % n);
    if (nodes[p] != null) {
      event(SandboxView.Severity.WARN, "chaos", "stopping v" + p + ", the proposer of height " + h);
      stop(p);
    }
    return p;
  }

  private void applyBlocksAll() {
    for (int i = 0; i < n; i++) {
      applyBlocks(i);
    }
  }

  private void applyBlocks(int i) {
    ValidatorNode v = nodes[i];
    if (v != null) {
      Set<Integer> b = Set.copyOf(blocked.get(i));
      v.setBlockedPeers(b::contains);
    }
  }

  /** Runs {@code verify-chain} (read-only replay from genesis) on every validator's data. */
  public List<String> verifyAll() {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      int code =
          Tools.verify(
              genesis,
              dataDir.resolve("v" + i),
              new PrintStream(buf, true, StandardCharsets.UTF_8));
      String text =
          buf.toString(StandardCharsets.UTF_8).trim().replace('\n', ' ').replaceAll("\\s+", " ");
      out.add("v" + i + ": " + text);
      event(
          code == 0 ? SandboxView.Severity.GOOD : SandboxView.Severity.CRITICAL,
          "verify-chain",
          "v" + i + " " + text);
    }
    return out;
  }

  // ------------------------------------------------------------------ bots

  public void configureBots(
      int makers, int takers, int ratePerSec, int volatilityBps, boolean running) {
    bots.configure(makers, takers, ratePerSec, volatilityBps, running);
  }

  // ------------------------------------------------------------------ views

  public int validators() {
    return n;
  }

  public int gatewayPort(int i) {
    return gatewayPorts[i];
  }

  public boolean isUp(int i) {
    return nodes[i] != null;
  }

  public GenesisConfig genesisConfig() {
    return genesis;
  }

  int firstLive() {
    for (int i = 0; i < n; i++) {
      if (nodes[i] != null) {
        return i;
      }
    }
    return -1;
  }

  List<Integer> liveGatewayPorts() {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (nodes[i] != null && !isolatedFlags[i]) {
        out.add(gatewayPorts[i]);
      }
    }
    if (out.isEmpty()) {
      for (int i = 0; i < n; i++) {
        if (nodes[i] != null) {
          out.add(gatewayPorts[i]);
        }
      }
    }
    return out;
  }

  long maxHeight() {
    long h = 0;
    for (ValidatorNode v : nodes) {
      if (v != null) {
        h = Math.max(h, v.height());
      }
    }
    return h;
  }

  /** Everything a dashboard shows, consistent enough for display; cheap enough for 10 Hz. */
  public SandboxView.Snapshot snapshot() {
    ShadowReplica.Views sv = shadow.views(bots.fairPrices(), 40);
    List<SandboxView.Validator> vs = new ArrayList<>();
    int compared = 0;
    int matching = 0;
    long next = maxHeight() + 1;
    long[] valLatency = new long[2];
    for (int i = 0; i < n; i++) {
      ValidatorNode v = nodes[i];
      if (v == null) {
        vs.add(
            new SandboxView.Validator(
                i,
                false,
                isolatedFlags[i],
                -1,
                "",
                "",
                false,
                false,
                List.of(),
                gatewayPorts[i],
                0,
                roundBumps.get(i),
                revokes.get(i),
                equivocations.get(i),
                decided.get(i),
                0,
                0,
                restarts[i],
                recoveryMillis[i]));
        continue;
      }
      Messages.ChainInfo ci = v.chainInfo();
      byte[] shadowRoot = shadow.rootAt(ci.height());
      boolean match = shadowRoot != null && Arrays.equals(shadowRoot, ci.stateRoot());
      if (shadowRoot != null) {
        compared++;
        if (match) {
          matching++;
        }
      }
      List<Long> ages = new ArrayList<>();
      for (Messages.ChainInfo.Validator pv : ci.validators()) {
        ages.add(pv.ageMillis());
      }
      Histogram h = v.metrics().histogram("gateway_ingest_to_final");
      long p50 = h == null || h.getTotalCount() == 0 ? 0 : h.getValueAtPercentile(50) / 1_000;
      long p99 = h == null || h.getTotalCount() == 0 ? 0 : h.getValueAtPercentile(99) / 1_000;
      valLatency[0] = Math.max(valLatency[0], p50);
      valLatency[1] = Math.max(valLatency[1], p99);
      vs.add(
          new SandboxView.Validator(
              i,
              true,
              isolatedFlags[i],
              ci.height(),
              HexFormat.of().formatHex(ci.headHash(), 0, 8),
              HexFormat.of().formatHex(ci.stateRoot(), 0, 8),
              match,
              next % n == i,
              ages,
              gatewayPorts[i],
              v.poolSize(),
              roundBumps.get(i),
              revokes.get(i),
              equivocations.get(i),
              decided.get(i),
              p50,
              p99,
              restarts[i],
              recoveryMillis[i]));
    }
    long[] lat = bots.latencies();
    long totalRevokes = 0;
    long totalBumps = 0;
    long totalEquiv = 0;
    for (int i = 0; i < n; i++) {
      totalRevokes += revokes.get(i);
      totalBumps = Math.max(totalBumps, roundBumps.get(i));
      totalEquiv += equivocations.get(i);
    }
    SandboxView.Stats stats =
        new SandboxView.Stats(
            sv.commands(),
            sv.accepted(),
            sv.rejected(),
            sv.tradeCount(),
            sv.height(),
            sv.commandsPerSec(),
            sv.tradesPerSec(),
            sv.blocksPerSec(),
            lat[0],
            lat[1],
            lat[2],
            lat[3],
            bots.sent(),
            bots.busy(),
            totalRevokes,
            totalBumps,
            totalEquiv,
            syncedBlocks.get());
    List<SandboxView.MyOrder> mine = new ArrayList<>();
    for (Tracked t : myOrders) {
      mine.add(t.view());
    }
    List<SandboxView.Event> ev;
    synchronized (events) {
      ev = List.copyOf(events);
    }
    return new SandboxView.Snapshot(
        System.currentTimeMillis(),
        sv.height(),
        genesis.stateRootVersion(),
        dataDir.toString(),
        sv.instruments(),
        sv.trades(),
        sv.blocks(),
        vs,
        ev,
        mine,
        sv.myOpen(),
        sv.myBalances(),
        stats,
        new SandboxView.Determinism(
            sv.height(),
            HexFormat.of().formatHex(sv.root(), 0, 8),
            compared,
            matching,
            compared == matching),
        bots.settings());
  }

  private void event(SandboxView.Severity s, String source, String text) {
    synchronized (events) {
      events.addFirst(new SandboxView.Event(System.currentTimeMillis(), s, source, text));
      while (events.size() > KEEP_EVENTS) {
        events.removeLast();
      }
    }
    LOG.atDebug()
        .setMessage("sandbox_event")
        .addKeyValue("source", source)
        .addKeyValue("text", text)
        .log();
  }

  /** A port free for both TCP (gateway, peer links) and UDP (heartbeats). */
  static int freePort() throws IOException {
    for (int attempt = 0; attempt < 50; attempt++) {
      try (ServerSocket s = new ServerSocket(0)) {
        int port = s.getLocalPort();
        try (java.net.DatagramSocket d = new java.net.DatagramSocket(port)) {
          return d.getLocalPort();
        } catch (java.net.SocketException taken) {
          // UDP side busy: try another
        }
      }
    }
    throw new IOException("no free TCP+UDP port");
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    bots.close();
    for (MizanClient c : myClients.values()) {
      try {
        c.close();
      } catch (IOException ignored) {
        // closing
      }
    }
    for (int i = 0; i < n; i++) {
      if (nodes[i] != null) {
        nodes[i].close();
        nodes[i] = null;
      }
    }
    shadow.close();
  }
}
