package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.chain.Recovery;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.FaultPoints;
import com.sajin.mizan.store.SnapshotStore;
import com.sajin.mizan.testkit.Seed;
import com.sajin.mizan.testkit.SeedExtension;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * M6-T10 / Gate M6: repeatedly crash a child-JVM node under load and recover it.
 *
 * <p>Each iteration starts a child on the same data directory. The child recovers whatever the
 * previous one left, then is killed either at a named fault point ({@code -Dmizan.fault}) or at a
 * random moment ({@code destroyForcibly}). The parent then checks:
 *
 * <ul>
 *   <li>recovery succeeds: a torn tail is truncated, the snapshot root verifies, and every replayed
 *       root equals its header;
 *   <li>an independent replay of the whole log from genesis, ignoring snapshots, reaches the same
 *       state root;
 *   <li>every {@code FINAL} a client received is in the chain at the reported height with the same
 *       order id.
 * </ul>
 *
 * <p>Iterations from {@code -Piter=N} (default 20; the gate uses 200).
 */
@Tag("recovery")
@ExtendWith(SeedExtension.class)
class CrashRecoveryTest {
  static final GenesisConfig G = DevGenesis.config();
  static final String[] FAULTS = {
    FaultPoints.BEFORE_APPEND,
    FaultPoints.MID_APPEND,
    FaultPoints.AFTER_APPEND,
    FaultPoints.MID_SNAPSHOT,
    FaultPoints.MID_BLOCK,
    "random-kill"
  };

  @TempDir Path dir;

  record Check(long height, long finalsChecked, long truncated) {}

  @Test
  void crashAndRecoverRepeatedly(Seed seed) throws Exception {
    int iterations = Integer.getInteger("mizan.iter", 20);
    SplittableRandom rnd = seed.random();
    String java = ProcessHandle.current().info().command().orElse("java");
    String cp = System.getProperty("java.class.path");
    Map<String, Integer> byFault = new TreeMap<>();
    Map<String, Integer> faultReached = new TreeMap<>();
    long finalsChecked = 0;
    long truncatedTotal = 0;
    Check last = null;
    for (int it = 0; it < iterations; it++) {
      String fault = FAULTS[it % FAULTS.length];
      List<String> cmd =
          new ArrayList<>(
              List.of(
                  java, "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED", "-Xmx512m"));
      if (!fault.equals("random-kill")) {
        cmd.add("-Dmizan.fault=" + fault + ":" + (1 + rnd.nextInt(12)));
      }
      cmd.addAll(
          List.of(
              "-Dmizan.log.level=WARN",
              "-cp",
              cp,
              CrashChild.class.getName(),
              dir.toString(),
              Integer.toString(it),
              "20000"));
      Process p =
          new ProcessBuilder(cmd)
              .redirectErrorStream(true)
              .redirectOutput(dir.resolve("child-" + it + ".out").toFile())
              .start();
      if (fault.equals("random-kill")) {
        p.waitFor(1_500 + rnd.nextInt(3_000), TimeUnit.MILLISECONDS);
      } else {
        p.waitFor(60, TimeUnit.SECONDS);
      }
      boolean selfHalted = !p.isAlive() && p.exitValue() == 137;
      p.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
      byFault.merge(fault, 1, Integer::sum);
      if (selfHalted) {
        faultReached.merge(fault, 1, Integer::sum);
      }
      last = verify(it);
      finalsChecked += last.finalsChecked();
      truncatedTotal += last.truncated();
    }
    System.out.printf(
        "recovery: %d iterations passed; kills per fault %s; fault point reached %s; final height %d; "
            + "%d client FINALs verified; %d torn bytes truncated%n",
        iterations, byFault, faultReached, last.height(), finalsChecked, truncatedTotal);
    assertThat(last.height()).isPositive();
    assertThat(finalsChecked).isPositive();
  }

  /** Recovers the data dir in-process and cross-checks it. */
  Check verify(int iteration) throws Exception {
    SignatureVerifier v = SignatureVerifier.ed25519();
    try (BlockStore store = BlockStore.open(dir.resolve("blocks.log"))) {
      long truncated = store.truncatedBytes();
      SnapshotStore snaps = new SnapshotStore(dir.resolve("snapshots"), 2);
      Recovery.Recovered r = Recovery.recover(G, v, store, snaps, 1);

      // Independent full replay from genesis, no snapshots (signatures already verified above).
      ExecutionCore replay = ExecutionCore.genesis(G, v, false);
      Map<String, long[]> committed = new HashMap<>(); // "account nonce" -> {height, orderId}
      for (int i = 0; i < store.size(); i++) {
        CommittedBlock cb = CommittedBlock.decode(store.read(i));
        for (SignedCommand c : cb.block().commands()) {
          ExecResult res = replay.execute(c, ExecutionCore.SILENT);
          committed.put(c.accountId() + " " + c.nonce(), new long[] {cb.height(), res.orderId()});
        }
        assertThat(replay.stateRoot())
            .as("root at height %d", cb.height())
            .isEqualTo(cb.block().header().stateRoot());
      }
      assertThat(replay.stateRoot())
          .as("iteration %d", iteration)
          .isEqualTo(r.committed().stateRoot());

      long checked = 0;
      List<Path> logs;
      try (Stream<Path> s = Files.list(dir)) {
        logs = s.filter(f -> f.getFileName().toString().startsWith("finals-")).toList();
      }
      for (Path log : logs) {
        for (String line : Files.readAllLines(log, StandardCharsets.US_ASCII)) {
          String[] f = line.trim().split(" ", -1);
          if (f.length != 4) {
            continue; // a line torn by the kill
          }
          long[] at = committed.get(f[0] + " " + f[1]);
          assertThat(at).as("FINAL %s survives", line).isNotNull();
          assertThat(at[0]).as("height of %s", line).isEqualTo(Long.parseLong(f[2]));
          assertThat(at[1]).as("order id of %s", line).isEqualTo(Long.parseLong(f[3]));
          checked++;
        }
      }
      assertThat(Arrays.equals(r.committed().stateRoot(), replay.stateRoot())).isTrue();
      return new Check(r.height(), checked, truncated);
    }
  }
}
