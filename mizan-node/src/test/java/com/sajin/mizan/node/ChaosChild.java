package com.sajin.mizan.node;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.testkit.Fixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Child JVM for {@link ChaosTest}: one rotating-proposer validator. Every 100 ms it reads {@code
 * control} (a comma-separated list of peer indexes, possibly empty) and drops all traffic to and
 * from those peers. It runs until the parent kills it.
 *
 * <p>Arguments: {@code genesis.json index gatewayPort dataDir controlFile}.
 */
public final class ChaosChild {
  private ChaosChild() {}

  public static void main(String[] args) throws Exception {
    GenesisConfig g = GenesisJson.read(Path.of(args[0]));
    int me = Integer.parseInt(args[1]);
    int gatewayPort = Integer.parseInt(args[2]);
    Path data = Path.of(args[3]);
    Path control = Path.of(args[4]);
    ValidatorNode v =
        ValidatorNode.start(
            Node.Settings.dev(g, gatewayPort),
            Node.Persistence.at(data).withSnapshotEvery(25),
            Fixtures.validatorSigner(me),
            me,
            List.of());
    System.out.println("READY " + me);
    Set<Integer> current = Set.of();
    while (true) {
      Set<Integer> next = new TreeSet<>();
      try {
        for (String s :
            Files.readString(control, StandardCharsets.US_ASCII).trim().split(",", -1)) {
          if (!s.isBlank()) {
            next.add(Integer.parseInt(s.trim()));
          }
        }
      } catch (java.io.IOException | NumberFormatException e) {
        next = new TreeSet<>(current); // half-written file: keep the previous setting
      }
      if (!next.equals(current)) {
        current = Set.copyOf(next);
        Set<Integer> blocked = current;
        v.network().setBlocked(blocked::contains);
        System.out.println("BLOCKED " + blocked);
      }
      if (v.failure() != null) {
        System.out.println("FAILURE " + v.failure());
        v.failure().printStackTrace(System.out);
        System.exit(3);
      }
      Thread.sleep(100);
    }
  }
}
