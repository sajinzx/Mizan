package com.sajin.mizan.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable genesis parameters (§5.8). Parsed from JSON in {@code node}; validated here so every
 * module sees a consistent configuration. Arrays are not copied.
 */
@SuppressWarnings("ArrayRecordComponent") // byte[] keys by design; compare with Arrays.equals
public record GenesisConfig(
    int chainId,
    int stateRootVersion,
    byte[] adminPubKey,
    List<Asset> assets,
    List<Instrument> instruments,
    List<Validator> validators,
    BlockParams block,
    ConsensusParams consensus) {

  /** A simulated asset. */
  public record Asset(int id, String name) {
    public Asset {
      Objects.requireNonNull(name, "name");
      if (id < 0 || id > Limits.MAX_ASSET) {
        throw new IllegalArgumentException("asset id out of range: " + id);
      }
    }
  }

  /** A consensus validator; {@code pubKey} is an X.509-encoded Ed25519 key. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Validator(int index, byte[] pubKey, String host, int port) {
    public Validator {
      Objects.requireNonNull(pubKey, "pubKey");
      Objects.requireNonNull(host, "host");
      if (index < 0) {
        throw new IllegalArgumentException("validator index must be >= 0");
      }
      if (port < 0 || port > 0xFFFF) {
        throw new IllegalArgumentException("port out of range: " + port);
      }
    }
  }

  /** Sequencer block-cut policy. */
  public record BlockParams(int maxCommands, long maxIntervalMs, int maxInflight) {
    public BlockParams {
      if (maxCommands <= 0 || maxIntervalMs <= 0 || maxInflight <= 0) {
        throw new IllegalArgumentException("block parameters must be positive");
      }
    }
  }

  /** Consensus timeouts: {@code timeout(r) = proposeTimeoutMs + r * timeoutDeltaMs}. */
  public record ConsensusParams(long proposeTimeoutMs, long timeoutDeltaMs) {
    public ConsensusParams {
      if (proposeTimeoutMs <= 0 || timeoutDeltaMs < 0) {
        throw new IllegalArgumentException("invalid consensus timeouts");
      }
    }
  }

  public GenesisConfig {
    Objects.requireNonNull(adminPubKey, "adminPubKey");
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(consensus, "consensus");
    assets = List.copyOf(assets);
    instruments = List.copyOf(instruments);
    validators = List.copyOf(validators);
    if (stateRootVersion != 1 && stateRootVersion != 2) {
      throw new IllegalArgumentException("unsupported stateRootVersion: " + stateRootVersion);
    }
    Set<Integer> assetIds = new HashSet<>();
    for (Asset a : assets) {
      if (!assetIds.add(a.id())) {
        throw new IllegalArgumentException("duplicate asset id: " + a.id());
      }
    }
    Set<Integer> instrumentIds = new HashSet<>();
    for (Instrument i : instruments) {
      if (!instrumentIds.add(i.id())) {
        throw new IllegalArgumentException("duplicate instrument id: " + i.id());
      }
      if (!assetIds.contains(i.base()) || !assetIds.contains(i.quote())) {
        throw new IllegalArgumentException("instrument " + i.id() + " references unknown asset");
      }
    }
    for (int k = 0; k < validators.size(); k++) {
      if (validators.get(k).index() != k) {
        throw new IllegalArgumentException("validator indexes must be 0..n-1 in order");
      }
    }
  }

  public Optional<Instrument> instrument(int id) {
    return instruments.stream().filter(i -> i.id() == id).findFirst();
  }
}
