package com.sajin.mizan.node;

import com.sajin.mizan.chain.Recovery;
import com.sajin.mizan.chain.bft.RoundBft;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.crypto.CachingVerifier;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.crypto.Signer;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.SnapshotStore;
import java.io.IOException;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A validator that votes and keeps a full replica (block log, snapshots, committed core) but runs
 * no sequencer or gateway. With the fixed proposer of M7, validators 1..n−1 are followers.
 */
public final class FollowerValidator implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(FollowerValidator.class);

  private final BftSetup.BftChain chain;

  private FollowerValidator(BftSetup.BftChain chain) {
    this.chain = chain;
  }

  public static FollowerValidator start(
      GenesisConfig g, Node.Persistence p, Signer key, int me, RoundBft.ProposerFn proposer)
      throws IOException {
    Files.createDirectories(p.dataDir());
    Node.checkAnchor(g, p.dataDir());
    CachingVerifier v = new CachingVerifier(SignatureVerifier.ed25519());
    BlockStore store = BlockStore.open(p.dataDir().resolve("blocks.log"));
    SnapshotStore snaps = new SnapshotStore(p.dataDir().resolve("snapshots"), p.keepSnapshots());
    Recovery.Recovered r = Recovery.recover(g, v, store, snaps, 1);
    BftSetup.BftChain chain =
        BftSetup.build(
            g,
            p,
            key,
            me,
            proposer,
            v,
            r,
            store,
            snaps,
            (h, cmds, results) -> {},
            new NodeMetrics());
    chain.start(null);
    LOG.atInfo()
        .setMessage("node_start")
        .addKeyValue("mode", "follower")
        .addKeyValue("validator", me)
        .addKeyValue("height", r.height())
        .addKeyValue("data", p.dataDir())
        .log();
    return new FollowerValidator(chain);
  }

  public long height() {
    return chain.height();
  }

  public Throwable failure() {
    return chain.driver.failure();
  }

  /** Only safe after {@link #close()}. */
  public ExecutionCore committedCore() {
    return chain.committedCore();
  }

  @Override
  public void close() {
    chain.close();
  }
}
