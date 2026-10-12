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

  /**
   * Consensus lifecycle log (M9-T2): round bumps, sync and peer bans as key-value events;
   * equivocation evidence is logged and appended to {@code dataDir/evidence.log}.
   */
  static RoundBft.Observer consensusLog(Path dataDir, int me) {
    Path file = dataDir.resolve("evidence.log");
    return new RoundBft.Observer() {
      @Override
      public void roundStarted(long height, int round) {
        if (round > 0) {
          LOG.atInfo()
              .setMessage("round_bump")
              .addKeyValue("validator", me)
              .addKeyValue("height", height)
              .addKeyValue("round", round)
              .log();
        }
      }

      @Override
      public void synced(int peer, long height, int blocks) {
        LOG.atInfo()
            .setMessage("synced")
            .addKeyValue("validator", me)
            .addKeyValue("peer", peer)
            .addKeyValue("height", height)
            .addKeyValue("blocks", blocks)
            .log();
      }

      @Override
      public void peerBanned(int peer, long untilMillis) {
        LOG.atWarn()
            .setMessage("peer_banned")
            .addKeyValue("validator", me)
            .addKeyValue("peer", peer)
            .addKeyValue("untilMillis", untilMillis)
            .log();
      }

      @Override
      public void equivocation(
          int validator, long height, int round, String kind, byte[] a, byte[] b) {
        String line =
            String.format(
                "%d validator=%d height=%d round=%d kind=%s first=%s second=%s seenBy=%d%n",
                System.currentTimeMillis(), validator, height, round, kind, hex(a), hex(b), me);
        LOG.atWarn()
            .setMessage("equivocation")
            .addKeyValue("validator", validator)
            .addKeyValue("height", height)
            .addKeyValue("round", round)
            .addKeyValue("kind", kind)
            .addKeyValue("first", hex(a))
            .addKeyValue("second", hex(b))
            .addKeyValue("seenBy", me)
            .log();
        try {
          Files.writeString(
              file,
              line,
              StandardCharsets.US_ASCII,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
        } catch (IOException e) {
          LOG.atError().setMessage("evidence_log_failed").setCause(e).log();
        }
      }
    };
  }

  /**
   * Peer-link authentication with validator keys (M9-T3). The chain id is prefixed to every
   * handshake transcript, so a key reused on another chain cannot be replayed here.
   */
  static TcpNetwork.PeerAuth peerAuth(GenesisConfig g, Signer key) {
    com.sajin.mizan.chain.ValidatorSet vs = new com.sajin.mizan.chain.ValidatorSet(g);
    SignatureVerifier ed = SignatureVerifier.ed25519();
    MessageSigner signer = MessageSigner.of(key);
    return new TcpNetwork.PeerAuth() {
      @Override
      public byte[] sign(byte[] message) {
        return signer.sign(withChain(g.chainId(), message));
      }

      @Override
      public boolean verify(int peer, byte[] message, byte[] signature) {
        return ed.verify(vs.key(peer), withChain(g.chainId(), message), signature);
      }
    };
  }

  private static byte[] withChain(int chainId, byte[] message) {
    return java.nio.ByteBuffer.allocate(4 + message.length).putInt(chainId).put(message).array();
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
        LOG.atWarn().setMessage("close_failed").addKeyValue("error", e.toString()).log();
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
        new TcpNetwork(
            me, peers(g), peerAuth(g, key), (from, msg) -> ref[0].onNetworkMessage(from, msg));
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
                metrics.observe(consensusLog(p.dataDir(), me))),
            recovered,
            store,
            snaps,
            finality);
    ref[0] = driver;
    return new BftChain(driver, net, store, safety);
  }
}
