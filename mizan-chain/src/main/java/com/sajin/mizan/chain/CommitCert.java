package com.sajin.mizan.chain;

import com.sajin.mizan.core.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code CommitCert = n:4 || (validatorIndex:4 || sig:64)*} (§5.7): precommit signatures over
 * {@link Votes#signBytes} for the committed block at the cert's round.
 */
public record CommitCert(int round, List<Sig> sigs) {
  public static final int MAX_SIGS = 1_024;

  /** One validator's precommit signature. The array is not copied. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Sig(int validatorIndex, byte[] signature) {
    public Sig {
      Objects.requireNonNull(signature, "signature");
      if (signature.length != 64) {
        throw new IllegalArgumentException("signature must be 64 bytes");
      }
    }
  }

  public CommitCert {
    sigs = List.copyOf(sigs);
  }

  /**
   * Encodes as {@code round:4 || n:4 || (index:4 || sig:64)*}. The round is included because the
   * signed vote bytes depend on it (§5.7 leaves it implicit; ADR 0013).
   */
  void write(ByteBuffer b) {
    b.putInt(round).putInt(sigs.size());
    for (Sig s : sigs) {
      b.putInt(s.validatorIndex()).put(s.signature());
    }
  }

  int encodedSize() {
    return 8 + 68 * sigs.size();
  }

  public byte[] encode() {
    ByteBuffer b = ByteBuffer.allocate(encodedSize());
    write(b);
    return b.array();
  }

  static CommitCert read(ByteBuffer b) throws ProtocolException {
    try {
      int round = b.getInt();
      int n = b.getInt();
      if (n < 0 || n > MAX_SIGS) {
        throw new ProtocolException("bad signature count " + n);
      }
      List<Sig> sigs = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        int index = b.getInt();
        byte[] sig = new byte[64];
        b.get(sig);
        sigs.add(new Sig(index, sig));
      }
      return new CommitCert(round, sigs);
    } catch (BufferUnderflowException e) {
      throw new ProtocolException("truncated commit cert");
    }
  }
}
