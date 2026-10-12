package com.sajin.mizan.node;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.crypto.CachingVerifier;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import com.sajin.mizan.net.ResultCache;
import com.sajin.mizan.net.TcpGateway;
import com.sajin.mizan.net.md.MarketDataService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires one node: speculative and committed cores, the sequencer, the chain layer and the TCP
 * gateway (§6.9 threads). Listener fan-out:
 *
 * <ul>
 *   <li>engine events (sequencer thread) → gateway (TRADE/REVOKE pushes) + extra listeners;
 *   <li>provisional results (sequencer thread) → signature pre-check key directory → gateway;
 *   <li>finality (consensus thread) → gateway (FINAL).
 * </ul>
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

  private final Settings settings;
  private final CachingVerifier verifier;
  private final ExecutionCore committed;
  private final InstantChain chain;
  private final Sequencer sequencer;
  private final TcpGateway gateway;
  private final MarketDataService marketData;

  private Node(Settings s, List<EngineListener> extraListeners) throws IOException {
    this.settings = s;
    this.verifier = new CachingVerifier(SignatureVerifier.ed25519());
    ExecutionCore genesisCore = ExecutionCore.genesis(s.genesis(), verifier);
    this.committed = genesisCore.copy();
    SignaturePreCheck preCheck = new SignaturePreCheck(verifier);
    preCheck.learn(Accounts.ADMIN_ACCOUNT, s.genesis().adminPubKey());
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
    this.chain = new InstantChain(committed, gateway);
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
            genesisCore,
            Listeners.engine(engineListeners),
            Listeners.results(List.of(preCheck, gateway)),
            chain,
            s.sequencer(),
            System::nanoTime);
    seqRef[0] = sequencer;
  }

  /** Builds and starts an in-memory node (no persistence). */
  public static Node startInMemory(Settings s, List<EngineListener> extraListeners)
      throws IOException {
    Node n = new Node(s, extraListeners);
    n.sequencer.start();
    n.chain.start(n.sequencer);
    n.gateway.start();
    LOG.info(
        "node up: chainId={} gateway=127.0.0.1:{} (in-memory, instant commit)",
        s.genesis().chainId(),
        n.gateway.port());
    return n;
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

  /**
   * The committed core. Only safe to read after {@link #close()} (it is owned by the consensus
   * thread while running).
   */
  public ExecutionCore committedCore() {
    return committed;
  }

  public Settings settings() {
    return settings;
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
    LOG.info("node stopped at height {}", chain.height());
  }
}
