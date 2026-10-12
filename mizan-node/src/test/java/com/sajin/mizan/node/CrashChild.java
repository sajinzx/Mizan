package com.sajin.mizan.node;

import com.sajin.mizan.core.OrderType;
import com.sajin.mizan.core.Place;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.sim.LoadGenerator;
import com.sajin.mizan.sim.MizanClient;
import com.sajin.mizan.testkit.CommandSigner;
import com.sajin.mizan.testkit.Fixtures;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Child JVM for {@link CrashRecoveryTest}: recovers a persistent node from {@code dataDir}, drives
 * it with a few traders, and appends every {@code FINAL} a client receives to {@code
 * finals-<iteration>.log} ({@code account nonce height orderId}, flushed per line). It is killed by
 * a fault point ({@code -Dmizan.fault}) or by the parent.
 */
public final class CrashChild {
  private CrashChild() {}

  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    int iteration = Integer.parseInt(args[1]);
    long runMillis = Long.parseLong(args[2]);
    Node node =
        Node.startPersistent(
            Node.Settings.dev(DevGenesis.config(), 0),
            Node.Persistence.at(dir).withSnapshotEvery(5),
            Fixtures.validatorSigner(0),
            List.of(),
            List.of());
    int traders = 3;
    long firstAccount = 10_000L + iteration * 10L;
    LoadGenerator.setup(
        LoadGenerator.Params.of(
            "127.0.0.1", node.gatewayPort(), traders, 0, firstAccount, iteration));
    Path log = dir.resolve("finals-" + iteration + ".log");
    BufferedWriter out =
        Files.newBufferedWriter(
            log, StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    long deadline = System.nanoTime() + runMillis * 1_000_000L;
    long nonceBase = System.currentTimeMillis() * 1_000;
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < traders; t++) {
      long account = firstAccount + t;
      SplittableRandom rnd = new SplittableRandom(iteration * 31L + t);
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    try (MizanClient c = new MizanClient("127.0.0.1", node.gatewayPort())) {
                      for (long k = 0; System.nanoTime() < deadline; k++) {
                        long nonce = nonceBase + k;
                        SignedCommand cmd =
                            CommandSigner.ED25519.sign(
                                Fixtures.CHAIN_ID,
                                account,
                                nonce,
                                new Place(
                                    k,
                                    Fixtures.INSTRUMENT,
                                    rnd.nextBoolean() ? Side.BUY : Side.SELL,
                                    OrderType.LIMIT,
                                    rnd.nextInt(3) == 0 ? TimeInForce.IOC : TimeInForce.GTC,
                                    95L + rnd.nextInt(11),
                                    1L + rnd.nextInt(5)));
                        Messages.ServerMessage m = c.submit(cmd, Duration.ofSeconds(30));
                        if (m instanceof Messages.Accepted || m instanceof Messages.Rejected) {
                          var unused =
                              c.finality(account, nonce)
                                  .thenAccept(
                                      f -> {
                                        synchronized (out) {
                                          try {
                                            out.write(
                                                account
                                                    + " "
                                                    + nonce
                                                    + " "
                                                    + f.height()
                                                    + " "
                                                    + f.orderId()
                                                    + "\n");
                                            out.flush();
                                          } catch (java.io.IOException ignored) {
                                            // dying
                                          }
                                        }
                                      });
                        }
                      }
                    } catch (Exception e) {
                      // killed or shutting down
                    }
                  }));
    }
    for (Thread t : threads) {
      t.join();
    }
    node.close();
    System.exit(0);
  }
}
