package com.sajin.mizan.testkit;

import com.sajin.mizan.core.Accounts;
import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.core.Instrument;
import com.sajin.mizan.core.Limits;
import com.sajin.mizan.crypto.CryptoException;
import com.sajin.mizan.crypto.Hashing;
import com.sajin.mizan.crypto.Signer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic test fixtures. Every key is derived from a fixed label, so the same account or
 * validator always has the same key across runs and machines. Never use these keys outside tests
 * and demos.
 */
public final class Fixtures {
  public static final int CHAIN_ID = 4242;
  public static final int BASE = 1;
  public static final int QUOTE = 2;
  public static final int ALT = 3;
  public static final int INSTRUMENT = 1;
  public static final int ALT_INSTRUMENT = 2;

  private static final Map<String, Signer> SIGNERS = new ConcurrentHashMap<>();

  private Fixtures() {}

  /** The fixed signing key for {@code account}. Account 1 is the genesis admin. */
  public static Signer accountSigner(long account) {
    return signer("account", account);
  }

  public static Signer adminSigner() {
    return accountSigner(Accounts.ADMIN_ACCOUNT);
  }

  /** The fixed signing key for validator {@code index}. */
  public static Signer validatorSigner(int index) {
    return signer("validator", index);
  }

  private static Signer signer(String kind, long id) {
    return SIGNERS.computeIfAbsent(
        kind + ":" + id,
        k -> {
          byte[] seed =
              Hashing.sha256(
                  ("mizan-fixture-" + kind).getBytes(StandardCharsets.US_ASCII),
                  ByteBuffer.allocate(Long.BYTES).putLong(id).array());
          try {
            return Signer.fromSeed(seed);
          } catch (CryptoException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** {@code BASE/QUOTE}: tick 1, lot 1, maker 0 bps, taker 10 bps (the §5.8 example). */
  public static Instrument instrument() {
    return new Instrument(INSTRUMENT, BASE, QUOTE, 1, 1, 0, 10, Limits.MAX_PRICE, Limits.MAX_QTY);
  }

  /** {@code ALT/QUOTE}: tick 1, lot 1, maker 5 bps, taker 20 bps. */
  public static Instrument altInstrument() {
    return new Instrument(
        ALT_INSTRUMENT, ALT, QUOTE, 1, 1, 5, 20, Limits.MAX_PRICE, Limits.MAX_QTY);
  }

  /** Single-validator genesis with both fixture instruments. */
  public static GenesisConfig genesis() {
    return genesis(1);
  }

  /** Genesis with {@code validators} validators on consecutive localhost ports from 7000. */
  public static GenesisConfig genesis(int validators) {
    List<GenesisConfig.Validator> vs = new ArrayList<>();
    for (int i = 0; i < validators; i++) {
      vs.add(new GenesisConfig.Validator(i, validatorSigner(i).publicKey(), "127.0.0.1", 7000 + i));
    }
    return new GenesisConfig(
        CHAIN_ID,
        1,
        adminSigner().publicKey(),
        List.of(
            new GenesisConfig.Asset(BASE, "BASE"),
            new GenesisConfig.Asset(QUOTE, "QUOTE"),
            new GenesisConfig.Asset(ALT, "ALT")),
        List.of(instrument(), altInstrument()),
        vs,
        new GenesisConfig.BlockParams(500, 50, 1),
        new GenesisConfig.ConsensusParams(1000, 500));
  }
}
