package com.sajin.mizan.node;

import com.sajin.mizan.chain.ChainDriver;
import com.sajin.mizan.chain.GenesisAnchor;
import com.sajin.mizan.chain.Recovery;
import com.sajin.mizan.chain.SingleNodeConsensus;
import com.sajin.mizan.chain.ValidatorSet;
import com.sajin.mizan.chain.bft.RoundBft;
import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.crypto.CachingVerifier;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.crypto.Signer;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import com.sajin.mizan.net.ResultCache;
import com.sajin.mizan.net.TcpGateway;
import com.sajin.mizan.net.md.MarketDataService;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.SnapshotStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires one node: speculative and committed cores, the sequencer, the chain layer and the TCP
 * gateway (§6.9 threads). Listener fan-out:
 *
 * <ul>
 *   <li>engine events (sequencer thread) → gateway (TRADE/REVOKE pushes), market data, extras;
 *   <li>provisional results (sequencer thread) → signature pre-check key directory → gateway;
 *   <li>finality (consensus thread) → gateway (FINAL) + extras.
 * </ul>
 *
 * <p>Two chain layers: {@link #startInMemory} (instant commit, no persistence) and {@link
 * #startPersistent} (block log + snapshots + single-node consensus, recovered on start).
 */
public final class Node implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(Node.class);

  /** Node settings. */
  public record Settings(
      GenesisConfig genesis,
      TcpGateway.Config gateway,
      Sequencer.Config sequencer,
      boolean gatewayPreCheck,
      int marketDataPort,
      int resultCacheCapacity) {
    /** Gateway on {@code port}, pre-check on, market data disabled. */
    public static Settings dev(GenesisConfig g, int port) {
      return new Settings(
          g,
          TcpGateway.Config.defaults(port),
          Sequencer.Config.of(
              g.block().maxCommands(), g.block().maxIntervalMs(), g.block().maxInflight()),
          true,
          -1,
          1 << 20);
    }

    /** Enables UDP market data on {@code udpPort} ({@code 0} = ephemeral). */
    public Settings withMarketData(int udpPort) {
      return new Settings(
          genesis, gateway, sequencer, gatewayPreCheck, udpPort, resultCacheCapacity);
    }

    /** Bounds the gateway's result cache (total entries across accounts). */
    public Settings withResultCache(int capacity) {
      return new Settings(genesis, gateway, sequencer, gatewayPreCheck, marketDataPort, capacity);
    }
  }

  /** Persistence tuning. {@code failHook} lets tests reject drafts (M6-T8); null in production. */
  public record Persistence(
      Path dataDir,
      int snapshotEvery,
      int keepSnapshots,
      Predicate<Sequencer.BlockDraft> failHook) {
    public static Persistence at(Path dataDir) {
      return new Persistence(dataDir, 100, 2, null);
    }

    public Persistence withSnapshotEvery(int n) {
      return new Persistence(dataDir, n, keepSnapshots, failHook);
    }

    public Persistence withFailHook(Predicate<Sequencer.BlockDraft> hook) {
      return new Persistence(dataDir, snapshotEvery, keepSnapshots, hook);
    }
  }

  private final Settings settings;
  private final CachingVerifier verifier;
  private final ChainLayer chain;
  private final Sequencer sequencer;
  private final TcpGateway gateway;
  private final MarketDataService marketData;
  private final List<AutoCloseable> extraClosers = new ArrayList<>();

  private Node(
      Settings s,
      CachingVerifier verifier,
      ExecutionCore committedStart,
      Function<FinalityListener, ChainLayer> chainFactory,
      List<EngineListener> extraListeners,
      List<FinalityListener> extraFinality)
      throws IOException {
    this.settings = s;
    this.verifier = verifier;
    SignaturePreCheck preCheck = new SignaturePreCheck(verifier);
    preCheck.learn(Accounts.ADMIN_ACCOUNT, s.genesis().adminPubKey());
    committedStart.forEachAccount((account, nonce, pk) -> preCheck.learn(account, pk));
    ResultCache cache = new ResultCache(1024, s.resultCacheCapacity());
    // The gateway needs the sequencer as its sink, and the sequencer needs the gateway as a
    // listener: break the cycle with a forwarding sink.
    Sequencer[] seqRef = new Sequencer[1];
    this.gateway =
        new TcpGateway(
            c -> seqRef[0].submit(c),
            s.gatewayPreCheck() ? preCheck : TcpGateway.PreCheck.ACCEPT_ALL,
            cache,
            s.gateway());
    List<FinalityListener> finals = new ArrayList<>();
    finals.add(gateway);
    finals.addAll(extraFinality);
    this.chain = chainFactory.apply(Listeners.finality(finals));
    List<EngineListener> engineListeners = new ArrayList<>();
    engineListeners.add(gateway);
    if (s.marketDataPort() >= 0) {
      List<Integer> instruments = new ArrayList<>();
      s.genesis().instruments().forEach(i -> instruments.add(i.id()));
      this.marketData = new MarketDataService(1 << 16, s.marketDataPort(), 500, instruments);
      engineListeners.add(marketData);
      gateway.setSnapshotProvider(marketData::snapshot);
    } else {
      this.marketData = null;
    }
    engineListeners.addAll(extraListeners);
    this.sequencer =
        new Sequencer(
            committedStart.copy(),
            Listeners.engine(engineListeners),
            Listeners.results(List.of(preCheck, gateway)),
            chain,
            s.sequencer(),
            System::nanoTime);
    seqRef[0] = sequencer;
  }

  private void startThreads() throws IOException {
    sequencer.start();
    chain.start(sequencer);
    gateway.start();
  }

  /** Builds and starts an in-memory node (no persistence). */
  public static Node startInMemory(Settings s, List<EngineListener> extraListeners)
      throws IOException {
    CachingVerifier v = new CachingVerifier(SignatureVerifier.ed25519());
    ExecutionCore genesis = ExecutionCore.genesis(s.genesis(), v);
    Node n =
        new Node(
            s, v, genesis, f -> new InstantChain(genesis.copy(), f), extraListeners, List.of());
    n.startThreads();
    LOG.info(
        "node up: chainId={} gateway=127.0.0.1:{} (in-memory, instant commit)",
        s.genesis().chainId(),
        n.gateway.port());
    return n;
  }

  /**
   * Recovers from {@code p.dataDir()} (block log + snapshots) and starts a single-validator node
   * that signs blocks with {@code validatorKey}.
   */
  public static Node startPersistent(
      Settings s,
      Persistence p,
      Signer validatorKey,
      List<EngineListener> extraListeners,
      List<FinalityListener> extraFinality)
      throws IOException {
    GenesisConfig g = s.genesis();
    Files.createDirectories(p.dataDir());
    checkAnchor(g, p.dataDir());
    CachingVerifier v = new CachingVerifier(SignatureVerifier.ed25519());
    BlockStore store = BlockStore.open(p.dataDir().resolve("blocks.log"));
    SnapshotStore snaps = new SnapshotStore(p.dataDir().resolve("snapshots"), p.keepSnapshots());
    long t0 = System.nanoTime();
    Recovery.Recovered r = Recovery.recover(g, v, store, snaps, 64);
    LOG.info(
        "recovered height {} (snapshot {}, {} torn bytes truncated) in {} ms",
        r.height(),
        r.snapshotHeight(),
        store.truncatedBytes(),
        (System.nanoTime() - t0) / 1_000_000);
    SingleNodeConsensus consensus =
        new SingleNodeConsensus(new ValidatorSet(g), validatorKey, System::currentTimeMillis);
    Node n =
        new Node(
            s,
            v,
            r.committed(),
            f ->
                new PersistentChain(
                    new ChainDriver(r, consensus, store, snaps, p.snapshotEvery(), f, p.failHook()),
                    store),
            extraListeners,
            extraFinality);
    // Rebuild the result cache from the replayed tail of the chain (ADR 0003).
    for (Recovery.Replayed b : r.recent()) {
      n.gateway.onFinal(b.height(), b.commands(), b.results());
    }
    n.startThreads();
    LOG.info(
        "node up: chainId={} height={} gateway=127.0.0.1:{} data={}",
        g.chainId(),
        r.height(),
        n.gateway.port(),
        p.dataDir());
    return n;
  }

  static void checkAnchor(GenesisConfig g, Path dataDir) throws IOException {
    Path f = dataDir.resolve("genesis.anchor");
    String anchor = HexFormat.of().formatHex(GenesisAnchor.hash(g));
    if (Files.exists(f)) {
      String existing = Files.readString(f, StandardCharsets.US_ASCII).trim();
      if (!existing.equals(anchor)) {
        throw new PersistenceException(
            "data dir " + dataDir + " belongs to a different genesis (" + existing + ")");
      }
    } else {
      Files.writeString(f, anchor + "\n", StandardCharsets.US_ASCII);
    }
  }

  /**
   * Starts BFT validator {@code me} that hosts the sequencer and client gateway (the proposer in
   * M7). Blocks are agreed with the other validators over TCP (genesis addresses); BFT mode forces
   * {@code maxInflight = 1}.
   */
  public static Node startBft(
      Settings s,
      Persistence p,
      Signer validatorKey,
      int me,
      RoundBft.ProposerFn proposer,
      List<EngineListener> extraListeners,
      List<FinalityListener> extraFinality)
      throws IOException {
    GenesisConfig g = s.genesis();
    Sequencer.Config sc = s.sequencer();
    Settings bft =
        new Settings(
            g,
            s.gateway(),
            new Sequencer.Config(
                sc.inboundCapacity(),
                sc.controlCapacity(),
                sc.maxBatch(),
                sc.cutIntervalNanos(),
                1),
            s.gatewayPreCheck(),
            s.marketDataPort(),
            s.resultCacheCapacity());
    Files.createDirectories(p.dataDir());
    checkAnchor(g, p.dataDir());
    CachingVerifier v = new CachingVerifier(SignatureVerifier.ed25519());
    BlockStore store = BlockStore.open(p.dataDir().resolve("blocks.log"));
    SnapshotStore snaps = new SnapshotStore(p.dataDir().resolve("snapshots"), p.keepSnapshots());
    Recovery.Recovered r = Recovery.recover(g, v, store, snaps, 64);
    Node n =
        new Node(
            bft,
            v,
            r.committed(),
            f -> BftSetup.build(g, p, validatorKey, me, proposer, v, r, store, snaps, f),
            extraListeners,
            extraFinality);
    for (Recovery.Replayed b : r.recent()) {
      n.gateway.onFinal(b.height(), b.commands(), b.results());
    }
    n.startThreads();
    LOG.info(
        "validator {} up at height {} with gateway 127.0.0.1:{}", me, r.height(), n.gateway.port());
    return n;
  }

  /** Adapts {@link ChainDriver} (and the block log it owns) to {@link ChainLayer}. */
  private static final class PersistentChain implements ChainLayer {
    private final ChainDriver driver;
    private final BlockStore store;

    PersistentChain(ChainDriver driver, BlockStore store) {
      this.driver = driver;
      this.store = store;
    }

    @Override
    public void accept(Sequencer.BlockDraft d) {
      driver.accept(d);
    }

    @Override
    public void start(Sequencer s) {
      driver.start(s);
    }

    @Override
    public long height() {
      return driver.height();
    }

    @Override
    public ExecutionCore committedCore() {
      return driver.committedCore();
    }

    @Override
    public void close() {
      driver.close();
      try {
        store.close();
      } catch (PersistenceException e) {
        LOG.warn("closing block log: {}", e.toString());
      }
    }
  }

  public int gatewayPort() {
    return gateway.port();
  }

  /** UDP market-data port, or {@code -1} if disabled. */
  public int marketDataPort() {
    return marketData == null ? -1 : marketData.udpPort();
  }

  public TcpGateway gateway() {
    return gateway;
  }

  public CachingVerifier verifier() {
    return verifier;
  }

  public long height() {
    return chain.height();
  }

  /** The committed core. Only safe to read after {@link #close()}. */
  public ExecutionCore committedCore() {
    return chain.committedCore();
  }

  public Settings settings() {
    return settings;
  }

  /** Closes {@code c} when the node closes (after the core components). */
  public void closeWith(AutoCloseable c) {
    extraClosers.add(c);
  }

  @Override
  public void close() {
    gateway.close();
    try {
      sequencer.stop();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    chain.close();
    if (marketData != null) {
      marketData.close();
    }
    for (AutoCloseable c : extraClosers) {
      try {
        c.close();
      } catch (Exception e) {
        LOG.warn("closing {}: {}", c, e.toString());
      }
    }
    LOG.info("node stopped at height {}", chain.height());
  }
}
