package com.sajin.mizan.node;

import com.sajin.mizan.chain.Recovery;
import com.sajin.mizan.chain.bft.BftDriver;
import com.sajin.mizan.chain.bft.MessageSigner;
import com.sajin.mizan.chain.bft.RoundBft;
import com.sajin.mizan.chain.bft.WalSafetyStore;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.crypto.Signer;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.engine.Sequencer;
import com.sajin.mizan.net.TcpNetwork;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.ConsensusWal;
import com.sajin.mizan.store.SnapshotStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds the BFT pieces of a validator: driver, peer network, evidence log (M7-T7/T8). */
final class BftSetup {
  private static final Logger LOG = LoggerFactory.getLogger(BftSetup.class);

  private BftSetup() {}

  static List<InetSocketAddress> peers(GenesisConfig g) {
    List<InetSocketAddress> out = new ArrayList<>();
    g.validators().forEach(v -> out.add(new InetSocketAddress(v.host(), v.port())));
    return out;
  }

  /** Logs equivocation evidence and appends it to {@code dataDir/evidence.log}. */
  static RoundBft.Observer evidenceLog(Path dataDir, int me) {
    Path file = dataDir.resolve("evidence.log");
    return new RoundBft.Observer() {
      @Override
      public void equivocation(
          int validator, long height, int round, String kind, byte[] a, byte[] b) {
        String line =
            String.format(
                "%d validator=%d height=%d round=%d kind=%s first=%s second=%s seenBy=%d%n",
                System.currentTimeMillis(), validator, height, round, kind, hex(a), hex(b), me);
        LOG.warn("EQUIVOCATION {}", line.trim());
        try {
          Files.writeString(
              file,
              line,
              StandardCharsets.US_ASCII,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
        } catch (IOException e) {
          LOG.error("cannot write evidence log", e);
        }
      }
    };
  }

  private static String hex(byte[] h) {
    return h == null ? "nil" : HexFormat.of().formatHex(h, 0, 8);
  }

  /** A validator's chain layer: consensus driver + peer network + block log. */
  static final class BftChain implements ChainLayer {
    final BftDriver driver;
    final TcpNetwork network;
    final BlockStore store;
    final WalSafetyStore safety;

    BftChain(BftDriver driver, TcpNetwork network, BlockStore store, WalSafetyStore safety) {
      this.driver = driver;
      this.network = network;
      this.store = store;
      this.safety = safety;
    }

    @Override
    public void accept(Sequencer.BlockDraft d) {
      driver.accept(d);
    }

    @Override
    public void start(Sequencer s) {
      try {
        network.start();
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      driver.start(network, s);
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
      network.close();
      driver.close();
      try {
        store.close();
        safety.close();
      } catch (PersistenceException e) {
        LOG.warn("closing block log: {}", e.toString());
      }
    }
  }

  /** Opens storage, recovers, and builds an unstarted {@link BftChain} for validator {@code me}. */
  static BftChain build(
      GenesisConfig g,
      Node.Persistence p,
      Signer key,
      int me,
      RoundBft.ProposerFn proposer,
      SignatureVerifier verifier,
      Recovery.Recovered recovered,
      BlockStore store,
      SnapshotStore snaps,
      FinalityListener finality,
      NodeMetrics metrics) {
    BftDriver[] ref = new BftDriver[1];
    WalSafetyStore safety =
        new WalSafetyStore(new ConsensusWal(p.dataDir().resolve("consensus.wal")));
    TcpNetwork net =
        new TcpNetwork(me, peers(g), (from, msg) -> ref[0].onNetworkMessage(from, msg));
    BftDriver driver =
        new BftDriver(
            new BftDriver.Config(
                g,
                me,
                MessageSigner.of(key),
                verifier,
                proposer,
                safety,
                p.snapshotEvery(),
                Math.max(200, g.consensus().proposeTimeoutMs() / 2),
                metrics.observe(evidenceLog(p.dataDir(), me))),
            recovered,
            store,
            snaps,
            finality);
    ref[0] = driver;
    return new BftChain(driver, net, store, safety);
  }
}
