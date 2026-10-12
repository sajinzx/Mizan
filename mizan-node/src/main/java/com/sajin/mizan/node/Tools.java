package com.sajin.mizan.node;

import com.sajin.mizan.chain.ChainVerifier;
import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.Deposit;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.OrderCommand;
import com.sajin.mizan.core.PersistenceException;
import com.sajin.mizan.core.Register;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.CryptoException;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.crypto.Signer;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.sim.MizanClient;
import com.sajin.mizan.store.BlockStore;
import com.sajin.mizan.store.SnapshotStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Operator tools (M9-T4): {@code verify-chain}, {@code keygen}, {@code faucet}. */
final class Tools {
  private Tools() {}

  /** {@code mizan verify-chain}: re-verify a data directory from genesis. */
  @Command(
      name = "verify-chain",
      mixinStandardHelpOptions = true,
      description =
          "Re-verify a data directory from genesis: links, commit certificates, replayed state"
              + " roots and snapshots. Read-only.")
  static final class VerifyChainCmd implements Callable<Integer> {
    @Option(names = "--data", required = true, description = "Data directory (blocks.log).")
    Path data;

    @Option(
        names = "--genesis",
        required = true,
        description = "Genesis JSON the data directory was created with.")
    Path genesis;

    @Override
    public Integer call() throws Exception {
      return verify(GenesisJson.read(genesis), data, System.out);
    }
  }

  /** Verifies {@code data} against {@code g}, printing a report; 0 if valid, 1 otherwise. */
  static int verify(GenesisConfig g, Path data, PrintStream out) {
    long t0 = System.nanoTime();
    Path log = data.resolve("blocks.log");
    if (!Files.exists(log)) {
      out.println("FAILED: no block log at " + log);
      return 1;
    }
    try {
      Path anchor = data.resolve("genesis.anchor");
      String expected = HexFormat.of().formatHex(com.sajin.mizan.chain.GenesisAnchor.hash(g));
      if (Files.exists(anchor) && !Files.readString(anchor).trim().equals(expected)) {
        out.println("FAILED: " + data + " belongs to a different genesis");
        return 1;
      }
      ChainVerifier.Report r;
      try (BlockStore store = BlockStore.openReadOnly(log)) {
        r =
            ChainVerifier.verify(
                g,
                SignatureVerifier.ed25519(),
                store,
                SnapshotStore.readAllValid(data.resolve("snapshots")));
      }
      out.printf(
          "OK: height %d, %d commands, %d snapshot(s) checked, %d torn tail byte(s) ignored, %d ms%n"
              + "head  %s%nroot  %s%n",
          r.height(),
          r.commands(),
          r.snapshotsChecked(),
          r.tornBytes(),
          (System.nanoTime() - t0) / 1_000_000,
          HexFormat.of().formatHex(r.headHash()),
          HexFormat.of().formatHex(r.stateRoot()));
      return 0;
    } catch (PersistenceException e) {
      out.println("FAILED: " + e.getMessage());
      return 1;
    } catch (IOException e) {
      out.println("FAILED: " + e);
      return 1;
    }
  }

  /** {@code mizan keygen}: a new Ed25519 key file. */
  @Command(
      name = "keygen",
      mixinStandardHelpOptions = true,
      description = "Generate an Ed25519 key file (base64 seed) and print its public key.")
  static final class KeygenCmd implements Callable<Integer> {
    @Option(names = "--out", required = true, description = "Key file to create (not overwritten).")
    Path out;

    @Override
    public Integer call() throws Exception {
      if (Files.exists(out)) {
        System.err.println(out + " already exists; refusing to overwrite a key");
        return 2;
      }
      Signer s = KeyFiles.generate(out);
      System.out.println("wrote " + out);
      System.out.println("public key " + HexFormat.of().formatHex(s.publicKey()));
      return 0;
    }
  }

  /** {@code mizan faucet}: register an account (optional) and deposit to it with the admin key. */
  @Command(
      name = "faucet",
      mixinStandardHelpOptions = true,
      description = "Deposit simulated assets to an account using the admin key.")
  static final class FaucetCmd implements Callable<Integer> {
    @Option(names = "--host", defaultValue = "127.0.0.1")
    String host;

    @Option(names = "--port", defaultValue = "9000")
    int port;

    @Option(names = "--chain-id", defaultValue = "4242")
    int chainId;

    @Option(
        names = "--admin-key",
        required = true,
        description = "Admin key file, or dev-admin for the public dev genesis.")
    String adminKey;

    @Option(names = "--account", required = true)
    long account;

    @Option(
        names = "--account-key",
        description = "Register the account first with this key (file or dev-account:<n>).")
    String accountKey;

    @Option(names = "--asset", required = true)
    int asset;

    @Option(names = "--amount", required = true)
    long amount;

    @Option(
        names = "--nonce",
        description =
            "Admin nonce (default: microseconds since the epoch, increasing across runs).")
    Long nonce;

    @Override
    public Integer call() throws Exception {
      try (MizanClient c = new MizanClient(host, port)) {
        if (accountKey != null) {
          Signer key = KeyFiles.load(accountKey);
          Messages.ServerMessage r =
              await(c, sign(key, chainId, account, 1, new Register(key.publicKey())));
          System.out.println("register " + account + ": " + r);
        }
        long n =
            nonce != null
                ? nonce
                : java.time.temporal.ChronoUnit.MICROS.between(
                    java.time.Instant.EPOCH, java.time.Instant.now());
        Messages.ServerMessage r =
            await(
                c,
                sign(
                    KeyFiles.load(adminKey),
                    chainId,
                    Accounts.ADMIN_ACCOUNT,
                    n,
                    new Deposit(account, asset, amount)));
        System.out.println(
            "deposit " + amount + " of asset " + asset + " to " + account + ": " + r);
        return r instanceof Messages.Final f && f.status() == Messages.Final.ACCEPTED ? 0 : 1;
      }
    }
  }

  static SignedCommand sign(Signer key, int chainId, long account, long nonce, OrderCommand body)
      throws CryptoException {
    byte[] unsigned = CommandCodec.encodeUnsigned(chainId, account, nonce, body);
    try {
      return CommandCodec.assemble(unsigned, key.sign(unsigned));
    } catch (com.sajin.mizan.core.ProtocolException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Submits {@code cmd} and waits for its final answer (or a rejection / BUSY). */
  static Messages.ServerMessage await(MizanClient c, SignedCommand cmd) throws Exception {
    Messages.ServerMessage m = c.submit(cmd, Duration.ofSeconds(30));
    if (m instanceof Messages.Accepted) {
      return c.finality(cmd.accountId(), cmd.nonce()).get(30, TimeUnit.SECONDS);
    }
    return m;
  }
}
