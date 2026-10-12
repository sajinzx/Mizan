package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.chain.ChainVerifier;
import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.net.ChainInfoFetcher;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.sim.MizanClient;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.SnapshotStore;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M9-T7 chaos run. Four rotating-proposer validators run in their own JVMs under continuous client
 * load. A chaos loop repeatedly does one of: kill a validator with {@code destroyForcibly} (kill
 * -9) and restart it from its data directory, partition the cluster 2|2 (no quorum anywhere), or
 * isolate one validator; then heals and pauses. {@code -Pminutes=N} sets the duration (gate: 30).
 *
 * <p>Gate M9 checks, after a final heal: every validator converges to a common height; every data
 * directory passes {@code verify-chain} (certificates and replayed roots from genesis); block hash
 * and state root are identical across all four at every height; every client {@code FINAL} matches
 * the chain; and conservation holds for every asset on the replayed state.
 */
@Tag("chaos")
class ChaosTest {
  @TempDir Path dir;

  static final int N = 4;
  static final int TRADERS = 8;

  record Final(long height, long orderId) {}

  final Process[] procs = new Process[N];
  final int[] gatewayPorts = new int[N];
  final Map<String, Integer> actions = new TreeMap<>();
  String java;
  String cp;
  Path genesisFile;

  @Test
  void chaos() throws Exception {
    long minutes = Long.getLong("mizan.minutes", 2);
    GenesisConfig g = BftClusterTest.genesisOnFreePorts(N);
    genesisFile = dir.resolve("genesis.json");
    GenesisJson.write(g, genesisFile);
    java = ProcessHandle.current().info().command().orElse("java");
    cp = System.getProperty("java.class.path");
    for (int i = 0; i < N; i++) {
      gatewayPorts[i] = BftClusterTest.freeTcpAndUdpPort();
      control(i, "");
      start(i);
    }
    Map<String, Final> seen = new ConcurrentHashMap<>();
    AtomicLong attempts = new AtomicLong();
    AtomicLong failovers = new AtomicLong();
    AtomicLong unresolved = new AtomicLong();
    try {
      awaitAllUp();
      fund();
      long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
      List<Thread> traders = new ArrayList<>();
      for (int t = 0; t < TRADERS; t++) {
        int trader = t;
        traders.add(
            Thread.ofPlatform()
                .name("chaos-trader-" + t)
                .start(() -> trade(trader, end, seen, attempts, failovers, unresolved)));
      }
      SplittableRandom rnd = new SplittableRandom(Long.getLong("mizan.seed", 7));
      long maxLag = 0;
      while (System.nanoTime() < end) {
        chaosStep(rnd);
        maxLag = Math.max(maxLag, lag());
      }
      for (Thread t : traders) {
        t.join();
      }
      // Final heal: everyone up, no partitions; all must converge.
      for (int i = 0; i < N; i++) {
        control(i, "");
        if (!procs[i].isAlive()) {
          start(i);
        }
      }
      awaitAllUp();
      long target = maxHeight() + 3;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      while (minHeight() < target && System.nanoTime() < deadline) {
        Thread.sleep(200);
      }
      long converged = minHeight();
      System.out.printf(
          "chaos: %d min, actions %s, client attempts %d, failovers %d, FINALs %d, unresolved %d,"
              + " max height spread during chaos %d, converged at >= %d (target %d)%n",
          minutes,
          actions,
          attempts.get(),
          failovers.get(),
          seen.size(),
          unresolved.get(),
          maxLag,
          converged,
          target);
      assertThat(converged)
          .as("all validators converge after the final heal")
          .isGreaterThanOrEqualTo(target);
      for (int i = 0; i < N; i++) {
        assertThat(procs[i].isAlive())
            .as("validator %d still running (no FAILURE exit)", i)
            .isTrue();
      }
    } finally {
      for (Process p : procs) {
        if (p != null) {
          p.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
        }
      }
    }
    verifyAfterwards(g, seen);
  }

  // ------------------------------------------------------------------ chaos

  void chaosStep(SplittableRandom rnd) throws Exception {
    int kind = rnd.nextInt(4);
    long hold = 2_000L + rnd.nextInt(8_000);
    switch (kind) {
      case 0, 1 -> {
        int v = rnd.nextInt(N);
        procs[v].destroyForcibly().waitFor(30, TimeUnit.SECONDS);
        actions.merge("kill", 1, Integer::sum);
        Thread.sleep(hold);
        start(v);
      }
      case 2 -> {
        int a = rnd.nextInt(N);
        int b = (a + 1 + rnd.nextInt(N - 1)) % N;
        List<Integer> left = List.of(a, b);
        for (int i = 0; i < N; i++) {
          StringBuilder blocked = new StringBuilder();
          for (int j = 0; j < N; j++) {
            if (left.contains(i) != left.contains(j)) {
              blocked.append(blocked.isEmpty() ? "" : ",").append(j);
            }
          }
          control(i, blocked.toString());
        }
        actions.merge("partition-2-2", 1, Integer::sum);
        Thread.sleep(hold);
        healAll();
      }
      default -> {
        int v = rnd.nextInt(N);
        for (int i = 0; i < N; i++) {
          control(i, i == v ? others(v) : Integer.toString(v));
        }
        actions.merge("isolate", 1, Integer::sum);
        Thread.sleep(hold);
        healAll();
      }
    }
    Thread.sleep(1_000 + rnd.nextInt(4_000)); // quiet period
  }

  static String others(int v) {
    StringBuilder sb = new StringBuilder();
    for (int j = 0; j < N; j++) {
      if (j != v) {
        sb.append(sb.isEmpty() ? "" : ",").append(j);
      }
    }
    return sb.toString();
  }

  void healAll() throws IOException {
    for (int i = 0; i < N; i++) {
      control(i, "");
    }
  }

  void control(int i, String blocked) throws IOException {
    Path f = dir.resolve("control-" + i);
    Path tmp = dir.resolve("control-" + i + ".tmp");
    Files.writeString(tmp, blocked, StandardCharsets.US_ASCII);
    for (int attempt = 0; ; attempt++) {
      try {
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return;
      } catch (java.nio.file.AccessDeniedException e) { // Windows: the child is reading it
        if (attempt > 50) {
          throw e;
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  void start(int i) throws IOException {
    List<String> cmd =
        List.of(
            java,
            "--add-exports",
            "java.base/jdk.internal.misc=ALL-UNNAMED",
            "-Xmx384m",
            "-Dmizan.log.level=WARN",
            "-cp",
            cp,
            ChaosChild.class.getName(),
            genesisFile.toString(),
            Integer.toString(i),
            Integer.toString(gatewayPorts[i]),
            dir.resolve("v" + i).toString(),
            dir.resolve("control-" + i).toString());
    procs[i] =
        new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(
                ProcessBuilder.Redirect.appendTo(dir.resolve("v" + i + ".out").toFile()))
            .start();
  }

  // ------------------------------------------------------------------ observation

  Messages.ChainInfo info(int i) {
    try {
      return new ChainInfoFetcher(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), gatewayPorts[i]))
          .fetch();
    } catch (IOException e) {
      return null;
    }
  }

  long minHeight() {
    long min = Long.MAX_VALUE;
    for (int i = 0; i < N; i++) {
      Messages.ChainInfo c = info(i);
      min = Math.min(min, c == null ? -1 : c.height());
    }
    return min;
  }

  long maxHeight() {
    long max = 0;
    for (int i = 0; i < N; i++) {
      Messages.ChainInfo c = info(i);
      max = Math.max(max, c == null ? 0 : c.height());
    }
    return max;
  }

  long lag() {
    long min = Long.MAX_VALUE;
    long max = 0;
    for (int i = 0; i < N; i++) {
      Messages.ChainInfo c = info(i);
      if (c != null) {
        min = Math.min(min, c.height());
        max = Math.max(max, c.height());
      }
    }
    return min == Long.MAX_VALUE ? 0 : max - min;
  }

  void awaitAllUp() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      boolean all = true;
      for (int i = 0; i < N; i++) {
        all &= info(i) != null;
      }
      if (all) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("validators did not come up");
  }

  // ------------------------------------------------------------------ load

  MizanClient connect(int at) throws IOException {
    return new MizanClient("127.0.0.1", gatewayPorts[at]);
  }

  /** Submits {@code cmd} at validator {@code at} and waits for FINAL, or returns null. */
  static Messages.Final finalOf(MizanClient c, SignedCommand cmd) throws Exception {
    Messages.ServerMessage m = c.submit(cmd, Duration.ofSeconds(10));
    if (m instanceof Messages.Final f) {
      return f;
    }
    if (m instanceof Messages.Busy) {
      return null;
    }
    return c.finality(cmd.accountId(), cmd.nonce()).get(15, TimeUnit.SECONDS);
  }

  void fund() throws Exception {
    long adminNonce = 1;
    for (int t = 0; t < TRADERS; t++) {
      long acct = 900L + t;
      submitAnywhere(
          CommandSigner.ED25519.sign(
              Fixtures.CHAIN_ID, acct, 1, new Register(Fixtures.accountSigner(acct).publicKey())));
      for (int asset : new int[] {Fixtures.QUOTE, Fixtures.BASE, Fixtures.ALT}) {
        submitAnywhere(
            CommandSigner.ED25519.sign(
                Fixtures.CHAIN_ID,
                Accounts.ADMIN_ACCOUNT,
                adminNonce++,
                new Deposit(acct, asset, 10_000_000)));
      }
    }
  }

  void submitAnywhere(SignedCommand cmd) throws Exception {
    for (int attempt = 0; attempt < 20; attempt++) {
      try (MizanClient c = connect(attempt % N)) {
        if (finalOf(c, cmd) != null) {
          return;
        }
      } catch (Exception e) {
        Thread.sleep(500);
      }
    }
    throw new AssertionError("could not get FINAL for setup command " + cmd.accountId());
  }

  void trade(
      int trader,
      long end,
      Map<String, Final> seen,
      AtomicLong attempts,
      AtomicLong failovers,
      AtomicLong unresolved) {
    long account = 900L + trader;
    SplittableRandom rnd = new SplittableRandom(trader * 7919L);
    int at = trader % N;
    MizanClient c = null;
    for (long k = 0; System.nanoTime() < end; k++) {
      long nonce = 2 + k;
      SignedCommand cmd =
          CommandSigner.ED25519.sign(
              Fixtures.CHAIN_ID,
              account,
              nonce,
              new Place(
                  k,
                  rnd.nextBoolean() ? Fixtures.INSTRUMENT : Fixtures.ALT_INSTRUMENT,
                  rnd.nextBoolean() ? Side.BUY : Side.SELL,
                  OrderType.LIMIT,
                  rnd.nextInt(3) == 0 ? TimeInForce.IOC : TimeInForce.GTC,
                  95L + rnd.nextInt(11),
                  1L + rnd.nextInt(5)));
      Messages.Final f = null;
      for (int attempt = 0; f == null && attempt < 8; attempt++) {
        attempts.incrementAndGet();
        try {
          if (c == null) {
            c = connect(at);
          }
          f = finalOf(c, cmd);
        } catch (Exception e) {
          closeQuietly(c);
          c = null;
          at = (at + 1 + rnd.nextInt(N - 1)) % N; // fail over, same nonce
          failovers.incrementAndGet();
          try {
            Thread.sleep(200);
          } catch (InterruptedException ie) {
            return;
          }
        }
      }
      if (f == null) {
        unresolved.incrementAndGet(); // e.g. retried after its nonce was already used
      } else {
        seen.put(account + "/" + nonce, new Final(f.height(), f.orderId()));
      }
    }
    closeQuietly(c);
  }

  static void closeQuietly(MizanClient c) {
    if (c != null) {
      try {
        c.close();
      } catch (IOException ignored) {
        // already broken
      }
    }
  }

  // ------------------------------------------------------------------ verification

  void verifyAfterwards(GenesisConfig g, Map<String, Final> seen) throws Exception {
    List<List<CommittedBlock>> chains = new ArrayList<>();
    int checked = 0;
    for (int i = 0; i < N; i++) {
      Path data = dir.resolve("v" + i);
      try (BlockStore s = BlockStore.openReadOnly(data.resolve("blocks.log"))) {
        ChainVerifier.Report r =
            ChainVerifier.verify(
                g,
                SignatureVerifier.ed25519(),
                s,
                SnapshotStore.readAllValid(data.resolve("snapshots")));
        checked += r.snapshotsChecked();
        List<CommittedBlock> blocks = new ArrayList<>();
        for (int k = 0; k < s.size(); k++) {
          blocks.add(CommittedBlock.decode(s.read(k)));
        }
        chains.add(blocks);
      }
    }
    int common = chains.stream().mapToInt(List::size).min().orElseThrow();
    for (int h = 0; h < common; h++) {
      CommittedBlock ref = chains.get(0).get(h);
      for (int i = 1; i < N; i++) {
        CommittedBlock other = chains.get(i).get(h);
        assertThat(other.block().hash())
            .as("block hash at height %d, v%d", h + 1, i)
            .isEqualTo(ref.block().hash());
        assertThat(other.block().header().stateRoot())
            .as("state root at height %d, v%d", h + 1, i)
            .isEqualTo(ref.block().header().stateRoot());
      }
    }
    // Replay the longest chain: client FINALs and conservation.
    List<CommittedBlock> longest =
        chains.stream().max((a, b) -> Integer.compare(a.size(), b.size())).orElseThrow();
    ExecutionCore core = ExecutionCore.genesis(g, CommandSigner.FAST_VERIFIER, false);
    Map<String, Final> chain = new java.util.HashMap<>();
    long commands = 0;
    for (CommittedBlock cb : longest) {
      for (SignedCommand c : cb.block().commands()) {
        ExecResult r = core.execute(c, ExecutionCore.SILENT);
        chain.put(c.accountId() + "/" + c.nonce(), new Final(cb.height(), r.orderId()));
        commands++;
      }
    }
    for (Map.Entry<String, Final> e : seen.entrySet()) {
      assertThat(chain.get(e.getKey())).as("client FINAL %s", e.getKey()).isEqualTo(e.getValue());
    }
    for (GenesisConfig.Asset a : g.assets()) {
      assertThat(core.ledgerTotal(a.id()))
          .as("conservation asset %d", a.id())
          .isEqualTo(core.supply(a.id()));
    }
    System.out.printf(
        "chaos verify: %d validators verify-chain OK, %d snapshots checked, identical hash+root at"
            + " all %d common heights, %d commands, %d client FINALs match, head %s%n",
        N,
        checked,
        common,
        commands,
        seen.size(),
        HexFormat.of().formatHex(Arrays.copyOf(longest.get(longest.size() - 1).block().hash(), 8)));
  }
}
