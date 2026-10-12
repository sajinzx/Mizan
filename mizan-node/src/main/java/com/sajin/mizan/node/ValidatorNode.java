package com.sajin.mizan.node;

import com.sajin.mizan.chain.Recovery;
import com.sajin.mizan.chain.bft.BftDriver;
import com.sajin.mizan.chain.bft.CommandPool;
import com.sajin.mizan.chain.bft.MessageSigner;
import com.sajin.mizan.chain.bft.RoundBft;
import com.sajin.mizan.chain.bft.WalSafetyStore;
import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.crypto.CachingVerifier;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.crypto.Signer;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.net.ResultCache;
import com.sajin.mizan.net.TcpGateway;
import com.sajin.mizan.net.TcpNetwork;
import com.sajin.mizan.net.UdpHeartbeat;
import com.sajin.mizan.net.md.MarketDataService;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.ConsensusWal;
import com.sajin.mizan.store.SnapshotStore;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A validator with a rotating proposer (M8). Every validator runs the same thing:
 *
 * <ul>
 *   <li>a client gateway whose commands go into the local {@link CommandPool} and are gossiped to
 *       every validator (forwarding, M8-T2);
 *   <li>consensus over TCP with proposer {@code (h + r) mod n}; the proposer builds its block from
 *       its pool, executing it optimistically, and only its own clients get provisional acks
 *       (M8-T3);
 *   <li>on every decision: fsynced block log, committed core, {@code FINAL} to local clients,
 *       committed trades and depth to the gateway and market data, {@code REVOKE} if this
 *       validator's own proposal lost;
 *   <li>UDP heartbeats that make lagging validators bulk-sync (M8-T4/T5).
 * </ul>
 */
public final class ValidatorNode implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ValidatorNode.class);

  private final BftDriver driver;
  private final TcpNetwork network;
  private final UdpHeartbeat heartbeat;
  private final TcpGateway gateway;
  private final MarketDataService marketData;
  private final BlockStore store;
  private final WalSafetyStore safety;
  private final CommandPool pool;

  private ValidatorNode(
      BftDriver driver,
      TcpNetwork network,
      UdpHeartbeat heartbeat,
      TcpGateway gateway,
      MarketDataService marketData,
      BlockStore store,
      WalSafetyStore safety,
      CommandPool pool) {
    this.driver = driver;
    this.network = network;
    this.heartbeat = heartbeat;
    this.gateway = gateway;
    this.marketData = marketData;
    this.store = store;
    this.safety = safety;
    this.pool = pool;
  }

  public static ValidatorNode start(
      Node.Settings s, Node.Persistence p, Signer key, int me, List<EngineListener> extraCommitted)
      throws IOException {
    GenesisConfig g = s.genesis();
    Files.createDirectories(p.dataDir());
    Node.checkAnchor(g, p.dataDir());
    CachingVerifier v = new CachingVerifier(SignatureVerifier.ed25519());
    BlockStore store = BlockStore.open(p.dataDir().resolve("blocks.log"));
    SnapshotStore snaps = new SnapshotStore(p.dataDir().resolve("snapshots"), p.keepSnapshots());
    Recovery.Recovered r = Recovery.recover(g, v, store, snaps, 64);
    WalSafetyStore safety =
        new WalSafetyStore(new ConsensusWal(p.dataDir().resolve("consensus.wal")));
    CommandPool pool = new CommandPool(100_000);

    SignaturePreCheck preCheck = new SignaturePreCheck(v);
    preCheck.learn(Accounts.ADMIN_ACCOUNT, g.adminPubKey());
    r.committed().forEachAccount((account, nonce, pk) -> preCheck.learn(account, pk));

    BftDriver[] ref = new BftDriver[1];
    TcpGateway gateway =
        new TcpGateway(
            c -> {
              switch (pool.add(c, true, System.currentTimeMillis())) {
                case FULL -> {
                  return false; // BUSY
                }
                case NEW -> {
                  ref[0].gossip(c);
                  ref[0].poke();
                }
                case DUPLICATE -> {}
              }
              return true;
            },
            s.gatewayPreCheck() ? preCheck : TcpGateway.PreCheck.ACCEPT_ALL,
            new ResultCache(1024, s.resultCacheCapacity()),
            s.gateway());

    List<EngineListener> committedListeners = new ArrayList<>();
    committedListeners.add(gateway);
    MarketDataService md = null;
    if (s.marketDataPort() >= 0) {
      List<Integer> instruments = new ArrayList<>();
      g.instruments().forEach(i -> instruments.add(i.id()));
      md = new MarketDataService(1 << 16, s.marketDataPort(), 500, instruments);
      committedListeners.add(md);
      gateway.setSnapshotProvider(md::snapshot);
    }
    committedListeners.addAll(extraCommitted);
    FinalityListener finality = Listeners.finality(List.of(preCheck, gateway));

    TcpNetwork net =
        new TcpNetwork(me, BftSetup.peers(g), (from, msg) -> ref[0].onNetworkMessage(from, msg));
    BftDriver driver =
        new BftDriver(
            new BftDriver.Config(
                g,
                me,
                MessageSigner.of(key),
                v,
                RoundBft.ProposerFn.rotating(new com.sajin.mizan.chain.ValidatorSet(g)),
                safety,
                p.snapshotEvery(),
                Math.max(200, g.consensus().proposeTimeoutMs() / 2),
                BftSetup.evidenceLog(p.dataDir(), me)),
            r,
            store,
            snaps,
            finality,
            new BftDriver.Rotation(pool, gateway, Listeners.engine(committedListeners), 1_000));
    ref[0] = driver;
    UdpHeartbeat hb =
        new UdpHeartbeat(
            me, BftSetup.peers(g), driver::height, driver::headHash, driver::onPeerHeight, 500, 0);
    for (Recovery.Replayed b : r.recent()) {
      gateway.onFinal(b.height(), b.commands(), b.results());
    }
    net.start();
    driver.start(net, null);
    hb.start();
    gateway.start();
    LOG.info(
        "validator {} up at height {} (rotating proposer), gateway 127.0.0.1:{}",
        me,
        r.height(),
        gateway.port());
    return new ValidatorNode(driver, net, hb, gateway, md, store, safety, pool);
  }

  public int gatewayPort() {
    return gateway.port();
  }

  public int marketDataPort() {
    return marketData == null ? -1 : marketData.udpPort();
  }

  public long height() {
    return driver.height();
  }

  public int poolSize() {
    return pool.size();
  }

  public Throwable failure() {
    return driver.failure();
  }

  /** Only safe after {@link #close()}. */
  public ExecutionCore committedCore() {
    return driver.committedCore();
  }

  @Override
  public void close() {
    gateway.close();
    heartbeat.close();
    network.close();
    driver.close();
    if (marketData != null) {
      marketData.close();
    }
    try {
      store.close();
      safety.close();
    } catch (PersistenceException e) {
      LOG.warn("closing storage: {}", e.toString());
    }
  }
}
