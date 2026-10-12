package com.sajin.mizan.chain.bft;

import com.sajin.mizan.chain.Block;
import com.sajin.mizan.core.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * What a validator must remember across crashes so it never contradicts itself (§6.7, M7-T5): where
 * it is, what it is locked on, and the last proposal / prevote / precommit it signed (as {@code
 * (height, round, hash)}; a {@code null} hash is nil).
 *
 * <p>Encoding: {@code height:8 round:4 lockedRound:4 [lockedBlock] then 3 × signed-record}, each
 * optional item prefixed by a presence byte.
 */
@SuppressWarnings("ArrayRecordComponent")
public record SafetyState(
    long height,
    int round,
    int lockedRound,
    Block lockedBlock,
    Signed proposal,
    Signed prevote,
    Signed precommit) {

  /** The last message of one kind signed at {@code (height, round)} for {@code hash}. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Signed(long height, int round, byte[] hash) {
    /** Whether signing {@code hash} at {@code (h, r)} would contradict this record. */
    boolean forbids(long h, int r, byte[] other) {
      if (h != height) {
        return h < height;
      }
      if (r != round) {
        return r < round;
      }
      return !java.util.Arrays.equals(hash, other);
    }
  }

  public static SafetyState initial(long height) {
    return new SafetyState(height, 0, -1, null, null, null, null);
  }

  public byte[] encode() {
    byte[] locked = lockedBlock == null ? new byte[0] : lockedBlock.encode();
    ByteBuffer b =
        ByteBuffer.allocate(8 + 4 + 4 + 1 + 4 + locked.length + 3 * (1 + 8 + 4 + 1 + 32));
    b.putLong(height).putInt(round).putInt(lockedRound);
    b.put((byte) (lockedBlock == null ? 0 : 1)).putInt(locked.length).put(locked);
    for (Signed s : new Signed[] {proposal, prevote, precommit}) {
      if (s == null) {
        b.put((byte) 0).putLong(0).putInt(0).put((byte) 0).put(new byte[32]);
      } else {
        b.put((byte) 1).putLong(s.height()).putInt(s.round());
        b.put((byte) (s.hash() == null ? 0 : 1)).put(s.hash() == null ? new byte[32] : s.hash());
      }
    }
    return b.array();
  }

  public static SafetyState decode(byte[] bytes) throws ProtocolException {
    try {
      ByteBuffer b = ByteBuffer.wrap(bytes);
      long h = b.getLong();
      int r = b.getInt();
      int lr = b.getInt();
      boolean hasLocked = b.get() == 1;
      byte[] locked = new byte[b.getInt()];
      b.get(locked);
      Block lb = hasLocked ? Block.decode(locked) : null;
      Signed[] s = new Signed[3];
      for (int i = 0; i < 3; i++) {
        boolean present = b.get() == 1;
        long sh = b.getLong();
        int sr = b.getInt();
        boolean hasHash = b.get() == 1;
        byte[] hash = new byte[32];
        b.get(hash);
        s[i] = present ? new Signed(sh, sr, hasHash ? hash : null) : null;
      }
      if (b.hasRemaining()) {
        throw new ProtocolException("trailing bytes in safety state");
      }
      return new SafetyState(h, r, lr, lb, s[0], s[1], s[2]);
    } catch (BufferUnderflowException | NegativeArraySizeException e) {
      throw new ProtocolException("malformed safety state");
    }
  }
}
