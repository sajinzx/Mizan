package com.sajin.mizan.testkit;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Structure-aware byte mutations for fuzzing (M9-T5). All Mizan wire formats are big-endian
 * sequences of fixed-width integers, length-prefixed byte strings and counted lists, so the
 * mutations target exactly those shapes: integer fields overwritten with boundary values (to hit
 * lengths, counts, heights, rounds and indexes), truncation and extension at arbitrary points,
 * splicing two valid messages, and duplicating or deleting a range (to desynchronise counts from
 * contents). Plain bit flips are kept for coverage. One to three mutations are stacked.
 */
public final class StructuredMutator {
  private static final int[] INTERESTING_INTS = {
    0,
    1,
    -1,
    2,
    31,
    32,
    33,
    63,
    64,
    65,
    127,
    128,
    255,
    256,
    1 << 16,
    1 << 20,
    (1 << 20) + 1,
    1 << 26,
    Integer.MAX_VALUE,
    Integer.MIN_VALUE
  };
  private static final long[] INTERESTING_LONGS = {
    0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 32, 1L << 62
  };

  private final SplittableRandom rnd;

  public StructuredMutator(SplittableRandom rnd) {
    this.rnd = rnd;
  }

  /** A mutated copy of {@code in}; {@code corpus} supplies splice partners (may be empty). */
  public byte[] mutate(byte[] in, List<byte[]> corpus) {
    byte[] out = in.clone();
    int ops = 1 + rnd.nextInt(3);
    for (int i = 0; i < ops; i++) {
      out = once(out, corpus);
    }
    return out;
  }

  private byte[] once(byte[] in, List<byte[]> corpus) {
    int op = rnd.nextInt(9);
    if (in.length == 0) {
      op = 6; // only extension makes sense
    }
    return switch (op) {
      case 0 -> {
        byte[] o = in.clone();
        o[rnd.nextInt(o.length)] ^= (byte) (1 << rnd.nextInt(8));
        yield o;
      }
      case 1 -> {
        byte[] o = in.clone();
        o[rnd.nextInt(o.length)] = (byte) new int[] {0, 0x7F, 0x80, 0xFF, 1}[rnd.nextInt(5)];
        yield o;
      }
      case 2, 3 -> overwrite(in, ByteBuffer.allocate(4).putInt(pick(INTERESTING_INTS)).array());
      case 4 -> overwrite(in, ByteBuffer.allocate(8).putLong(pick(INTERESTING_LONGS)).array());
      case 5 -> Arrays.copyOf(in, rnd.nextInt(in.length)); // truncate
      case 6 -> {
        byte[] extra = new byte[1 + rnd.nextInt(16)];
        rnd.nextBytes(extra);
        yield concat(in, extra, 0, extra.length);
      }
      case 7 -> {
        if (corpus.isEmpty()) {
          yield Arrays.copyOf(in, rnd.nextInt(in.length));
        }
        byte[] other = corpus.get(rnd.nextInt(corpus.size()));
        int cut = rnd.nextInt(in.length + 1);
        int from = other.length == 0 ? 0 : rnd.nextInt(other.length);
        yield concat(Arrays.copyOf(in, cut), other, from, other.length - from);
      }
      default -> {
        int a = rnd.nextInt(in.length);
        int len = 1 + rnd.nextInt(Math.min(64, in.length - a));
        if (rnd.nextBoolean()) { // duplicate [a, a+len)
          byte[] o = new byte[in.length + len];
          System.arraycopy(in, 0, o, 0, a + len);
          System.arraycopy(in, a, o, a + len, in.length - a);
          yield o;
        }
        byte[] o = new byte[in.length - len]; // delete [a, a+len)
        System.arraycopy(in, 0, o, 0, a);
        System.arraycopy(in, a + len, o, a, in.length - a - len);
        yield o;
      }
    };
  }

  private byte[] overwrite(byte[] in, byte[] value) {
    if (in.length < value.length) {
      return in.clone();
    }
    byte[] o = in.clone();
    System.arraycopy(value, 0, o, rnd.nextInt(in.length - value.length + 1), value.length);
    return o;
  }

  private int pick(int[] values) {
    return values[rnd.nextInt(values.length)];
  }

  private long pick(long[] values) {
    return values[rnd.nextInt(values.length)];
  }

  private static byte[] concat(byte[] a, byte[] b, int from, int len) {
    byte[] o = Arrays.copyOf(a, a.length + len);
    System.arraycopy(b, from, o, a.length, len);
    return o;
  }
}
