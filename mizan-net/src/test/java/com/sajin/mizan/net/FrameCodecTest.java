package com.sajin.mizan.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.testkit.Seed;
import com.sajin.mizan.testkit.SeedExtension;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** M4-T1: framing must survive any split of the byte stream. */
@ExtendWith(SeedExtension.class)
class FrameCodecTest {

  /** Delivers the underlying bytes in chunks whose sizes come from {@code sizes}, cycling. */
  static final class ChunkedInputStream extends InputStream {
    private final byte[] data;
    private final int[] sizes;
    private int pos;
    private int chunk;

    ChunkedInputStream(byte[] data, int... sizes) {
      this.data = data;
      this.sizes = sizes;
    }

    @Override
    public int read() {
      return pos < data.length ? data[pos++] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (pos >= data.length) {
        return -1;
      }
      int n = Math.min(Math.min(len, sizes[chunk++ % sizes.length]), data.length - pos);
      System.arraycopy(data, pos, b, off, n);
      pos += n;
      return n;
    }
  }

  static List<FrameCodec.Frame> sampleFrames() {
    List<FrameCodec.Frame> fs = new ArrayList<>();
    fs.add(new FrameCodec.Frame(FrameCodec.ORDER, new byte[0]));
    fs.add(new FrameCodec.Frame(FrameCodec.QUERY_STATUS, new byte[16]));
    byte[] big = new byte[3_000];
    for (int i = 0; i < big.length; i++) {
      big[i] = (byte) i;
    }
    fs.add(new FrameCodec.Frame(FrameCodec.ORDER, big));
    fs.add(new FrameCodec.Frame(0xFF, new byte[] {1, 2, 3}));
    return fs;
  }

  static byte[] concat(List<FrameCodec.Frame> fs) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (FrameCodec.Frame f : fs) {
      out.writeBytes(FrameCodec.encode(f.type(), f.payload()));
    }
    return out.toByteArray();
  }

  static List<FrameCodec.Frame> readAll(InputStream in) throws IOException {
    List<FrameCodec.Frame> out = new ArrayList<>();
    FrameCodec.Frame f;
    while ((f = FrameCodec.read(in)) != null) {
      out.add(f);
    }
    return out;
  }

  static void assertSameFrames(List<FrameCodec.Frame> actual, List<FrameCodec.Frame> expected) {
    assertThat(actual).hasSameSizeAs(expected);
    for (int i = 0; i < expected.size(); i++) {
      assertThat(actual.get(i).type()).isEqualTo(expected.get(i).type());
      assertThat(actual.get(i).payload()).isEqualTo(expected.get(i).payload());
    }
  }

  @Test
  void coalescedFramesOneByteAtATime() throws IOException {
    List<FrameCodec.Frame> fs = sampleFrames();
    assertSameFrames(readAll(new ChunkedInputStream(concat(fs), 1)), fs);
  }

  @Test
  void everySplitPointOfTheStream() throws IOException {
    List<FrameCodec.Frame> fs = sampleFrames();
    byte[] all = concat(fs);
    for (int k = 1; k < all.length; k++) {
      assertSameFrames(readAll(new ChunkedInputStream(all, k, all.length)), fs);
    }
  }

  @Test
  void randomChunkSizes(Seed seed) throws IOException {
    SplittableRandom rnd = seed.random();
    List<FrameCodec.Frame> fs = sampleFrames();
    byte[] all = concat(fs);
    for (int run = 0; run < 2_000; run++) {
      int[] sizes = new int[17];
      for (int i = 0; i < sizes.length; i++) {
        sizes[i] = rnd.nextInt(1, 64);
      }
      assertSameFrames(readAll(new ChunkedInputStream(all, sizes)), fs);
    }
  }

  @Test
  void truncationAtEveryLengthIsDetected() throws IOException {
    byte[] one = FrameCodec.encode(FrameCodec.ORDER, new byte[40]);
    assertThat(FrameCodec.read(new ByteArrayInputStream(new byte[0]))).isNull();
    for (int len = 1; len < one.length; len++) {
      byte[] cut = Arrays.copyOf(one, len);
      assertThatThrownBy(() -> FrameCodec.read(new ByteArrayInputStream(cut)))
          .isInstanceOf(EOFException.class);
    }
  }

  @Test
  void badLengthsAreProtocolErrors() {
    for (int len : new int[] {0, -1, FrameCodec.MAX_FRAME + 1, Integer.MAX_VALUE}) {
      byte[] hdr = ByteBuffer.allocate(5).putInt(len).put((byte) 1).array();
      assertThatThrownBy(() -> FrameCodec.read(new ByteArrayInputStream(hdr)))
          .isInstanceOf(ProtocolException.class);
    }
    assertThatThrownBy(() -> FrameCodec.encode(1, new byte[FrameCodec.MAX_FRAME]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FrameCodec.Frame(256, new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void serverMessagesRoundTrip() throws IOException {
    List<Messages.ServerMessage> all =
        List.of(
            new Messages.Busy(2, 3),
            new Messages.Accepted(2, 3, 99),
            new Messages.Rejected(2, 3, 5, "stale nonce é"),
            new Messages.Final(2, 3, 7, Messages.Final.ACCEPTED, 99),
            new Messages.Status(2, 3, Messages.Status.FINAL, 7, 99),
            new Messages.TradeMsg(new Trade(1, 1, 2, 3, 4, 5, Side.SELL, 100, 6, 0)),
            new Messages.Revoke(42),
            new Messages.Err(Messages.ERR_TIMEOUT));
    for (Messages.ServerMessage m : all) {
      FrameCodec.Frame f = FrameCodec.read(new ByteArrayInputStream(m.encode()));
      assertThat(Messages.decode(f)).isEqualTo(m);
    }
    byte[] snap = {9, 8};
    FrameCodec.Frame sf =
        FrameCodec.read(new ByteArrayInputStream(new Messages.Snapshot(snap).encode()));
    assertThat(((Messages.Snapshot) Messages.decode(sf)).payload()).isEqualTo(snap);
    assertThat(Messages.parseQuery(Arrays.copyOfRange(Messages.queryStatus(5, 6), 5, 21)))
        .containsExactly(5, 6);
  }

  @Test
  void malformedServerMessagesAreProtocolErrors() {
    assertThatThrownBy(() -> Messages.decode(new FrameCodec.Frame(FrameCodec.BUSY, new byte[3])))
        .isInstanceOf(ProtocolException.class);
    assertThatThrownBy(() -> Messages.decode(new FrameCodec.Frame(FrameCodec.BUSY, new byte[17])))
        .isInstanceOf(ProtocolException.class);
    assertThatThrownBy(() -> Messages.decode(new FrameCodec.Frame(0x7E, new byte[0])))
        .isInstanceOf(ProtocolException.class);
    assertThatThrownBy(() -> Messages.parseQuery(new byte[15]))
        .isInstanceOf(ProtocolException.class);
  }
}
