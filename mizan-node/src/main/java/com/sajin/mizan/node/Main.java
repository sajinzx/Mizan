package com.sajin.mizan.node;

import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.sim.LoadGenerator;
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
    subcommands = {Main.NodeCmd.class, Main.LoadCmd.class})
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

    @Override
    public Integer call() throws Exception {
      if (!dev) {
        System.err.println(
            "only --dev is available in this build; persistent nodes arrive with M6");
        return 2;
      }
      Node node = Node.startInMemory(Node.Settings.dev(DevGenesis.config(), port), List.of());
      CountDownLatch done = new CountDownLatch(1);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    node.close();
                    done.countDown();
                  },
                  "shutdown"));
      System.out.println("listening on 127.0.0.1:" + node.gatewayPort());
      done.await();
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
