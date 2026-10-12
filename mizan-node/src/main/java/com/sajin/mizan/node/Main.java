package com.sajin.mizan.node;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.sim.LoadGenerator;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code mizan} command line. */
@Command(
    name = "mizan",
    mixinStandardHelpOptions = true,
    version = "mizan 0.1.0",
    description = "Deterministic order-book chain (educational; simulated assets only).",
    subcommands = {Main.NodeCmd.class, Main.LoadCmd.class, Main.GenesisCmd.class})
public final class Main implements Callable<Integer> {

  public static void main(String[] args) {
    System.exit(new CommandLine(new Main()).execute(args));
  }

  @Override
  public Integer call() {
    CommandLine.usage(this, System.out);
    return 0;
  }

  /** {@code mizan node}: run a node until interrupted. */
  @Command(name = "node", mixinStandardHelpOptions = true, description = "Run a node.")
  static final class NodeCmd implements Callable<Integer> {
    @Option(names = "--port", defaultValue = "9000", description = "Gateway TCP port.")
    int port;

    @Option(
        names = "--dev",
        description = "In-memory node with the public dev genesis (no persistence).")
    boolean dev;

    @Option(
        names = "--md-port",
        defaultValue = "9001",
        description = "UDP market-data port (-1 = off).")
    int mdPort;

    @Option(names = "--genesis", description = "Genesis JSON (persistent mode).")
    Path genesis;

    @Option(names = "--data", description = "Data directory: block log and snapshots.")
    Path data;

    @Option(
        names = "--key",
        description = "Validator key file (base64 seed), or dev:<i> for a public dev key.")
    String key;

    @Option(
        names = "--snapshot-every",
        defaultValue = "100",
        description = "Blocks between snapshots.")
    int snapshotEvery;

    @Override
    public Integer call() throws Exception {
      Node node;
      if (dev) {
        node =
            Node.startInMemory(
                Node.Settings.dev(DevGenesis.config(), port).withMarketData(mdPort), List.of());
      } else {
        if (genesis == null || data == null || key == null) {
          System.err.println("persistent mode needs --genesis, --data and --key (or use --dev)");
          return 2;
        }
        GenesisConfig g = GenesisJson.read(genesis);
        node =
            Node.startPersistent(
                Node.Settings.dev(g, port).withMarketData(mdPort),
                Node.Persistence.at(data).withSnapshotEvery(snapshotEvery),
                KeyFiles.load(key),
                List.of(),
                List.of());
      }
      CountDownLatch done = new CountDownLatch(1);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    node.close();
                    done.countDown();
                  },
                  "shutdown"));
      System.out.println(
          "gateway 127.0.0.1:"
              + node.gatewayPort()
              + "  market data udp 127.0.0.1:"
              + node.marketDataPort()
              + "  height "
              + node.height());
      done.await();
      return 0;
    }
  }

  /** {@code mizan genesis --dev}: write the public dev genesis as JSON. */
  @Command(
      name = "genesis",
      mixinStandardHelpOptions = true,
      description = "Write a dev genesis JSON (public dev keys; local use only).")
  static final class GenesisCmd implements Callable<Integer> {
    @Option(names = "--validators", defaultValue = "1")
    int validators;

    @Option(names = "--out", required = true)
    Path out;

    @Override
    public Integer call() throws Exception {
      GenesisJson.write(DevGenesis.config(validators), out);
      System.out.println(
          "wrote " + out + " (validator keys: dev:0 .. dev:" + (validators - 1) + ")");
      return 0;
    }
  }

  /** {@code mizan load}: drive a node with concurrent traders. */
  @Command(
      name = "load",
      mixinStandardHelpOptions = true,
      description = "Generate load against a node.")
  static final class LoadCmd implements Callable<Integer> {
    @Option(names = "--host", defaultValue = "127.0.0.1")
    String host;

    @Option(names = "--port", defaultValue = "9000")
    int port;

    @Option(names = "--traders", defaultValue = "100")
    int traders;

    @Option(names = "--orders", defaultValue = "1000", description = "Orders per trader.")
    int orders;

    @Option(names = "--first-account", defaultValue = "1000")
    long firstAccount;

    @Override
    public Integer call() throws Exception {
      var p = LoadGenerator.Params.of(host, port, traders, orders, firstAccount, 1);
      LoadGenerator.setup(p);
      LoadGenerator.Result r = LoadGenerator.run(p);
      r.print(System.out);
      return r.errors() == 0 ? 0 : 1;
    }
  }
}
