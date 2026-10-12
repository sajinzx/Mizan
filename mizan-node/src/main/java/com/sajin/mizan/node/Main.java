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
    subcommands = {
      Main.NodeCmd.class,
      Main.LoadCmd.class,
      Main.GenesisCmd.class,
      Main.ClusterCmd.class,
      Tools.VerifyChainCmd.class,
      Tools.KeygenCmd.class,
      Tools.FaucetCmd.class
    })
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
        names = "--metrics-port",
        defaultValue = "-1",
        description = "Serve plain-text /metrics on 127.0.0.1 at this port (-1 = off).")
    int metricsPort;

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
      if (metricsPort >= 0) {
        node.closeWith(MetricsServer.start(metricsPort, node.metrics()::render));
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

  /**
   * {@code mizan cluster --nodes 4}: a local BFT cluster in one process (dev genesis, public dev
   * keys). Validator 0 is the proposer and hosts the client gateway and market data; the others are
   * followers. Each validator has its own data directory, block log and consensus WAL.
   */
  @Command(
      name = "cluster",
      mixinStandardHelpOptions = true,
      description = "Run a local n-validator BFT cluster (dev keys; local use only).")
  static final class ClusterCmd implements Callable<Integer> {
    @Option(names = "--nodes", defaultValue = "4")
    int nodes;

    @Option(names = "--data", defaultValue = "cluster-data")
    Path data;

    @Option(
        names = "--port",
        defaultValue = "9000",
        description = "Gateway TCP port of validator 0.")
    int port;

    @Option(names = "--md-port", defaultValue = "9001")
    int mdPort;

    @Option(
        names = "--metrics-port",
        defaultValue = "-1",
        description =
            "Serve plain-text /metrics on 127.0.0.1 at this port (-1 = off); validator i uses port + i.")
    int metricsPort;

    @Option(
        names = "--fixed-proposer",
        description = "M7 mode: validator 0 is the only proposer and hosts the only gateway.")
    boolean fixedProposer;

    @Override
    public Integer call() throws Exception {
      GenesisConfig g = DevGenesis.config(nodes);
      java.nio.file.Files.createDirectories(data);
      GenesisJson.write(g, data.resolve("genesis.json"));
      List<AutoCloseable> running = new java.util.ArrayList<>();
      StringBuilder ports = new StringBuilder();
      if (fixedProposer) {
        com.sajin.mizan.chain.bft.RoundBft.ProposerFn proposer =
            com.sajin.mizan.chain.bft.RoundBft.ProposerFn.fixed(0);
        Node leader =
            Node.startBft(
                Node.Settings.dev(g, port).withMarketData(mdPort),
                Node.Persistence.at(data.resolve("v0")),
                DevGenesis.validatorKey(0),
                0,
                proposer,
                List.of(),
                List.of());
        running.add(leader);
        if (metricsPort >= 0) {
          leader.closeWith(MetricsServer.start(metricsPort, leader.metrics()::render));
        }
        ports.append("gateway 127.0.0.1:").append(leader.gatewayPort());
        for (int i = 1; i < nodes; i++) {
          running.add(
              FollowerValidator.start(
                  g,
                  Node.Persistence.at(data.resolve("v" + i)),
                  DevGenesis.validatorKey(i),
                  i,
                  proposer));
        }
      } else {
        for (int i = 0; i < nodes; i++) {
          ValidatorNode v =
              ValidatorNode.start(
                  Node.Settings.dev(g, port + i).withMarketData(i == 0 ? mdPort : -1),
                  Node.Persistence.at(data.resolve("v" + i)),
                  DevGenesis.validatorKey(i),
                  i,
                  List.of());
          running.add(v);
          if (metricsPort >= 0) {
            running.add(MetricsServer.start(metricsPort + i, v.metrics()::render));
          }
          ports.append(i == 0 ? "gateways " : ", ").append("127.0.0.1:").append(v.gatewayPort());
        }
      }
      CountDownLatch done = new CountDownLatch(1);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    for (AutoCloseable c : running) {
                      try {
                        c.close();
                      } catch (Exception e) {
                        // shutting down
                      }
                    }
                    done.countDown();
                  },
                  "shutdown"));
      System.out.println(
          nodes
              + " validators up ("
              + (fixedProposer ? "fixed proposer" : "rotating proposer")
              + "); "
              + ports
              + "; market data udp 127.0.0.1:"
              + mdPort
              + "; data "
              + data.toAbsolutePath());
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

    @Option(
        names = "--instruments",
        defaultValue = "1",
        description = "Spread orders over 1 or 2 instruments (dev genesis).")
    int instruments;

    @Override
    public Integer call() throws Exception {
      var p =
          LoadGenerator.Params.of(host, port, traders, orders, firstAccount, 1)
              .withInstruments(instruments);
      LoadGenerator.setup(p);
      LoadGenerator.Result r = LoadGenerator.run(p);
      r.print(System.out);
      return r.errors() == 0 ? 0 : 1;
    }
  }
}
