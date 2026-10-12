package com.sajin.mizan.chain.bft;

import com.sajin.mizan.chain.Block;
import com.sajin.mizan.chain.CommittedBlock;
import com.sajin.mizan.chain.Votes;
import com.sajin.mizan.core.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Consensus messages (§6.8, docs/protocol.md) and their canonical encoding:
 *
 * <pre>
 * PROPOSAL [1][height:8][round:4][validRound:4][proposer:4][sig:64][blockLen:4][block]
 * VOTE     [2][type:1][height:8][round:4][hasHash:1][hash:32?][validator:4][sig:64]
 * COMMIT   [3][from:4][committedLen:4][committed block]   (catch-up for lagging validators)
 * </pre>
 *
 * Proposal signatures cover {@code "CBPROP1" || chainId || height || round || validRound ||
 * blockHash}; votes sign the §5.7 vote bytes. A commit is authenticated by its certificate, not by
 * the sender.
 */
public sealed interface ConsensusMessage {
  int TAG_PROPOSAL = 1;
  int TAG_VOTE = 2;
  int TAG_COMMIT = 3;

  /** A proposal for {@code (height, round)}. Arrays are not copied. */
  @SuppressWarnings("ArrayRecordComponent")
  record Proposal(
      long height, int round, int validRound, int proposer, Block block, byte[] signature)
      implements ConsensusMessage {
    public Proposal {
      Objects.requireNonNull(block, "block");
      Objects.requireNonNull(signature, "signature");
    }

    public static byte[] signBytes(
        int chainId, long height, int round, int validRound, byte[] blockHash) {
      byte[] tag = "CBPROP1".getBytes(StandardCharsets.US_ASCII);
      return ByteBuffer.allocate(tag.length + 4 + 8 + 4 + 4 + 32)
          .put(tag)
          .putInt(chainId)
          .putLong(height)
          .putInt(round)
          .putInt(validRound)
          .put(blockHash)
          .array();
    }
  }

  /** A prevote or precommit; {@code blockHash == null} is a nil vote. */
  @SuppressWarnings("ArrayRecordComponent")
  record Vote(int type, long height, int round, byte[] blockHash, int validator, byte[] signature)
      implements ConsensusMessage {
    public Vote {
      if (type != Votes.PREVOTE && type != Votes.PRECOMMIT) {
        throw new IllegalArgumentException("bad vote type " + type);
      }
      if (blockHash != null && blockHash.length != 32) {
        throw new IllegalArgumentException("hash must be 32 bytes");
      }
      Objects.requireNonNull(signature, "signature");
    }

    public boolean isNil() {
      return blockHash == null;
    }

    public boolean sameTarget(Vote o) {
      return Arrays.equals(blockHash, o.blockHash);
    }
  }

  /** A decided block and its certificate, sent to help a lagging validator catch up. */
  record Commit(int from, CommittedBlock committed) implements ConsensusMessage {}

  static byte[] encode(ConsensusMessage m) {
    return switch (m) {
      case Proposal p -> {
        byte[] block = p.block().encode();
        yield ByteBuffer.allocate(1 + 8 + 4 + 4 + 4 + 64 + 4 + block.length)
            .put((byte) TAG_PROPOSAL)
            .putLong(p.height())
            .putInt(p.round())
            .putInt(p.validRound())
            .putInt(p.proposer())
            .put(p.signature())
            .putInt(block.length)
            .put(block)
            .array();
      }
      case Vote v -> {
        ByteBuffer b = ByteBuffer.allocate(1 + 1 + 8 + 4 + 1 + (v.isNil() ? 0 : 32) + 4 + 64);
        b.put((byte) TAG_VOTE).put((byte) v.type()).putLong(v.height()).putInt(v.round());
        b.put((byte) (v.isNil() ? 0 : 1));
        if (!v.isNil()) {
          b.put(v.blockHash());
        }
        yield b.putInt(v.validator()).put(v.signature()).array();
      }
      case Commit c -> {
        byte[] cb = c.committed().encode();
        yield ByteBuffer.allocate(1 + 4 + 4 + cb.length)
            .put((byte) TAG_COMMIT)
            .putInt(c.from())
            .putInt(cb.length)
            .put(cb)
            .array();
      }
    };
  }

  static ConsensusMessage decode(byte[] bytes) throws ProtocolException {
    ByteBuffer b = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    try {
      int tag = Byte.toUnsignedInt(b.get());
      ConsensusMessage m =
          switch (tag) {
            case TAG_PROPOSAL -> {
              long h = b.getLong();
              int r = b.getInt();
              int vr = b.getInt();
              int proposer = b.getInt();
              byte[] sig = new byte[64];
              b.get(sig);
              byte[] block = new byte[length(b)];
              b.get(block);
              yield new Proposal(h, r, vr, proposer, Block.decode(block), sig);
            }
            case TAG_VOTE -> {
              int type = Byte.toUnsignedInt(b.get());
              if (type != Votes.PREVOTE && type != Votes.PRECOMMIT) {
                throw new ProtocolException("bad vote type " + type);
              }
              long h = b.getLong();
              int r = b.getInt();
              int has = Byte.toUnsignedInt(b.get());
              if (has > 1) {
                throw new ProtocolException("bad nil flag");
              }
              byte[] hash = null;
              if (has == 1) {
                hash = new byte[32];
                b.get(hash);
              }
              int validator = b.getInt();
              byte[] sig = new byte[64];
              b.get(sig);
              yield new Vote(type, h, r, hash, validator, sig);
            }
            case TAG_COMMIT -> {
              int from = b.getInt();
              byte[] cb = new byte[length(b)];
              b.get(cb);
              yield new Commit(from, CommittedBlock.decode(cb));
            }
            default -> throw new ProtocolException("unknown consensus message " + tag);
          };
      if (b.hasRemaining()) {
        throw new ProtocolException("trailing bytes in consensus message");
      }
      return m;
    } catch (BufferUnderflowException | IllegalArgumentException e) {
      throw new ProtocolException("malformed consensus message: " + e.getMessage());
    }
  }

  private static int length(ByteBuffer b) throws ProtocolException {
    int n = b.getInt();
    if (n < 0 || n > b.remaining()) {
      throw new ProtocolException("bad length " + n);
    }
    return n;
  }
}
