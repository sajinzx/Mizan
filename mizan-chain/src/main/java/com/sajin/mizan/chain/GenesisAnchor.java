package com.sajin.mizan.chain;

import com.sajin.mizan.core.GenesisConfig;
import com.sajin.mizan.crypto.Hashing;
import com.sajin.mizan.crypto.SignatureVerifier;
import com.sajin.mizan.engine.ExecutionCore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Height-0 anchor (M6-T2): {@code prevHash} of block 1 is {@code SHA256("MIZAN-GENESIS-V1" ||
 * chainId:4 || genesisStateRoot)}. Two nodes agree on the anchor if and only if they agree on the
 * chain id and on every genesis input that reaches the state root.
 */
public final class GenesisAnchor {
  private static final byte[] TAG = "MIZAN-GENESIS-V1".getBytes(StandardCharsets.US_ASCII);

  private GenesisAnchor() {}

  public static byte[] stateRoot(GenesisConfig g) {
    return ExecutionCore.genesis(g, SignatureVerifier.ed25519()).stateRoot();
  }

  public static byte[] hash(GenesisConfig g) {
    return Hashing.sha256(TAG, ByteBuffer.allocate(4).putInt(g.chainId()).array(), stateRoot(g));
  }
}
