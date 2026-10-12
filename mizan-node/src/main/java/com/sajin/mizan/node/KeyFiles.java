package com.sajin.mizan.node;

import com.sajin.mizan.crypto.CryptoException;
import com.sajin.mizan.crypto.Signer;
import com.sajin.mizan.sim.DevGenesis;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Validator and account key files: one line with the base64 of a 32-byte Ed25519 seed. Instead of a
 * path, {@code dev:<i>} selects the public dev validator key {@code i}, {@code dev-admin} the dev
 * admin key and {@code dev-account:<n>} the dev key of account {@code n} (local experiments only).
 */
public final class KeyFiles {
  private KeyFiles() {}

  public static Signer load(String spec) throws IOException {
    try {
      if (spec.startsWith("dev:")) {
        return DevGenesis.validatorKey(Integer.parseInt(spec.substring(4)));
      }
      if (spec.equals("dev-admin")) {
        return DevGenesis.adminKey();
      }
      if (spec.startsWith("dev-account:")) {
        return DevGenesis.accountKey(Long.parseLong(spec.substring(12)));
      }
      byte[] seed =
          Base64.getDecoder()
              .decode(Files.readString(Path.of(spec), StandardCharsets.US_ASCII).trim());
      return Signer.fromSeed(seed);
    } catch (CryptoException | IllegalArgumentException e) {
      throw new IOException("cannot load key " + spec + ": " + e.getMessage(), e);
    }
  }

  /** Generates a new key, writes its seed to {@code file}, and returns the signer. */
  public static Signer generate(Path file) throws IOException {
    byte[] seed = new byte[Signer.SEED_BYTES];
    new SecureRandom().nextBytes(seed);
    try {
      Signer s = Signer.fromSeed(seed);
      Files.writeString(
          file,
          Base64.getEncoder().encodeToString(seed) + System.lineSeparator(),
          StandardCharsets.US_ASCII);
      return s;
    } catch (CryptoException e) {
      throw new IOException(e);
    }
  }
}
