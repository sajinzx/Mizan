package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.StateCodec;
import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.sim.LoadGenerator;
import com.sajin.mizan.store.SnapshotStore;
import com.sajin.mizan.testkit.Fixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** M9-T4: verify-chain, keygen and faucet. */
@Timeout(180)
class ToolsTest {
  static final GenesisConfig G = DevGenesis.config();
  @TempDir Path dir;

  record Run(int code, String out) {}

  static Run verify(GenesisConfig g, Path data) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int code = Tools.verify(g, data, new PrintStream(buf, true, StandardCharsets.UTF_8));
    return new Run(code, buf.toString(StandardCharsets.UTF_8));
  }

  @Test
  void verifyChainAcceptsARealChainAndRejectsTampering() throws Exception {
    Path data = dir.resolve("data");
    com.sajin.mizan.net.Messages.ChainInfo info;
    Node node =
        Node.startPersistent(
            Node.Settings.dev(G, 0),
            Node.Persistence.at(data).withSnapshotEvery(3),
            Fixtures.validatorSigner(0),
            List.of(),
            List.of());
    try {
      var p = LoadGenerator.Params.of("127.0.0.1", node.gatewayPort(), 4, 30, 70, 5);
      LoadGenerator.setup(p);
      assertThat(LoadGenerator.run(p).errors()).isZero();
      NodeIntegrationTest.awaitHeightStable(node);
      info =
          new com.sajin.mizan.net.ChainInfoFetcher(
                  new java.net.InetSocketAddress(
                      java.net.InetAddress.getLoopbackAddress(), node.gatewayPort()))
              .fetch();
    } finally {
      node.close();
    }
    long height = node.height();
    assertThat(info.height()).isEqualTo(height);
    String root = HexFormat.of().formatHex(node.committedCore().stateRoot());

    Run ok = verify(G, data);
    assertThat(ok.code()).as(ok.out()).isZero();
    assertThat(ok.out()).contains("OK: height " + height).contains("root  " + root);
    assertThat(ok.out())
        .as("CHAIN_INFO agrees with verify-chain")
        .contains("head  " + HexFormat.of().formatHex(info.headHash()))
        .contains("root  " + HexFormat.of().formatHex(info.stateRoot()));
    assertThat(ok.out()).containsPattern("[12] snapshot\\(s\\) checked");

    // Read-only: a torn tail is reported, not truncated.
    Path log = data.resolve("blocks.log");
    long size = Files.size(log);
    Files.write(log, new byte[] {0, 0, 0, 9, 1}, java.nio.file.StandardOpenOption.APPEND);
    Run torn = verify(G, data);
    assertThat(torn.code()).isZero();
    assertThat(torn.out()).contains("5 torn tail byte(s) ignored");
    assertThat(Files.size(log)).isEqualTo(size + 5);

    // A different genesis is refused.
    Run other = verify(Fixtures.genesis(4), data);
    assertThat(other.code()).isOne();
    assertThat(other.out()).startsWith("FAILED");

    // A snapshot whose recorded root lies about the chain is caught.
    SnapshotStore snaps = new SnapshotStore(data.resolve("snapshots"), 10);
    byte[] state =
        StateCodec.encode(
            com.sajin.mizan.engine.ExecutionCore.genesis(G, SignatureVerifier.ed25519()));
    snaps.write(height, new byte[32], state);
    Run lying = verify(G, data);
    assertThat(lying.code()).isOne();
    assertThat(lying.out()).contains("snapshot at height " + height);

    // Corruption in the middle of the log is refused.
    Files.delete(data.resolve("snapshots").resolve(String.format("snapshot-%020d.bin", height)));
    byte[] bytes = Files.readAllBytes(log);
    bytes[20] ^= 0x40; // inside the first record's body: CRC mismatch with data after it
    Files.write(log, bytes);
    Run corrupt = verify(G, data);
    assertThat(corrupt.code()).isOne();
    assertThat(corrupt.out()).contains("corrupt");
  }

  @Test
  void keygenWritesALoadableKeyAndRefusesToOverwrite() throws Exception {
    Path key = dir.resolve("k.key");
    assertThat(new CommandLine(new Main()).execute("keygen", "--out", key.toString())).isZero();
    assertThat(KeyFiles.load(key.toString()).publicKey()).hasSize(44); // X.509-encoded Ed25519
    byte[] before = Files.readAllBytes(key);
    assertThat(new CommandLine(new Main()).execute("keygen", "--out", key.toString())).isEqualTo(2);
    assertThat(Files.readAllBytes(key)).isEqualTo(before);
  }

  @Test
  void faucetRegistersAndFunds() throws Exception {
    Node node = Node.startInMemory(Node.Settings.dev(G, 0), List.of());
    try {
      String port = String.valueOf(node.gatewayPort());
      assertThat(
              new CommandLine(new Main())
                  .execute(
                      "faucet",
                      "--port",
                      port,
                      "--admin-key",
                      "dev-admin",
                      "--account",
                      "777",
                      "--account-key",
                      "dev-account:777",
                      "--asset",
                      String.valueOf(Fixtures.QUOTE),
                      "--amount",
                      "5000"))
          .isZero();
      assertThat(
              new CommandLine(new Main())
                  .execute(
                      "faucet",
                      "--port",
                      port,
                      "--admin-key",
                      "dev-admin",
                      "--account",
                      "777",
                      "--asset",
                      String.valueOf(Fixtures.QUOTE),
                      "--amount",
                      "1000"))
          .isZero();
      // A key that is not the admin's cannot mint.
      assertThat(
              new CommandLine(new Main())
                  .execute(
                      "faucet",
                      "--port",
                      port,
                      "--admin-key",
                      "dev-account:777",
                      "--account",
                      "777",
                      "--asset",
                      String.valueOf(Fixtures.QUOTE),
                      "--amount",
                      "1"))
          .isOne();
      NodeIntegrationTest.awaitHeightStable(node);
    } finally {
      node.close();
    }
    assertThat(node.committedCore().free(777, Fixtures.QUOTE)).isEqualTo(6_000);
  }
}
