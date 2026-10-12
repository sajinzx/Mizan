package com.sajin.mizan.net.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Side;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MdCodecTest {

  static List<DepthBook.Level> levels(int n, long base, int dir) {
    List<DepthBook.Level> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(new DepthBook.Level(base + (long) dir * i, 10 + i));
    }
    return out;
  }

  @Test
  void depthPacketIsCappedAt35LevelsAndFitsTheBudget() throws ProtocolException {
    byte[] p = MdCodec.depth(7, 3, levels(50, 100, -1), levels(40, 101, 1));
    assertThat(p.length).isEqualTo(15 + 16 * 70).isLessThanOrEqualTo(MdCodec.MAX_PACKET);
    MdCodec.Depth d = (MdCodec.Depth) MdCodec.decode(p, p.length);
    assertThat(d.seq()).isEqualTo(7);
    assertThat(d.instrument()).isEqualTo(3);
    assertThat(d.bids()).hasSize(35).first().isEqualTo(new DepthBook.Level(100, 10));
    assertThat(d.asks()).hasSize(35).last().isEqualTo(new DepthBook.Level(135, 44));
  }

  @Test
  void otherPacketsRoundTrip() throws ProtocolException {
    byte[] r = MdCodec.revoke(9, 44);
    assertThat(MdCodec.decode(r, r.length)).isEqualTo(new MdCodec.Revoke(9, 44));
    byte[] h = MdCodec.heartbeat(12);
    assertThat(MdCodec.decode(h, h.length)).isEqualTo(new MdCodec.Heartbeat(12));
    byte[] t = MdCodec.trade(13, 2, 99, Side.SELL, 101, 4);
    assertThat(MdCodec.decode(t, t.length))
        .isEqualTo(new MdCodec.TradePrint(13, 2, 99, Side.SELL, 101, 4));
    assertThat(MdCodec.subscribe()).containsExactly(MdCodec.SUBSCRIBE);
  }

  @Test
  void snapshotRoundTripsWithAllLevels() throws ProtocolException {
    List<MdCodec.Book> books =
        List.of(
            new MdCodec.Book(1, levels(80, 100, -1), levels(3, 101, 1)),
            new MdCodec.Book(2, List.of(), List.of()));
    MdCodec.Snapshot s = MdCodec.decodeSnapshot(MdCodec.snapshot(55, books));
    assertThat(s.seq()).isEqualTo(55);
    assertThat(s.books()).isEqualTo(books);
  }

  @Test
  void malformedInputOnlyThrowsProtocolException() {
    byte[] good = MdCodec.depth(1, 1, levels(3, 100, -1), levels(3, 101, 1));
    for (int len = 0; len < good.length; len++) {
      int l = len;
      assertThatThrownBy(() -> MdCodec.decode(good, l)).isInstanceOf(ProtocolException.class);
    }
    byte[] longer = Arrays.copyOf(good, good.length + 1);
    assertThatThrownBy(() -> MdCodec.decode(longer, longer.length))
        .isInstanceOf(ProtocolException.class);
    assertThatThrownBy(() -> MdCodec.decode(new byte[] {77}, 1))
        .isInstanceOf(ProtocolException.class);
    byte[] snap = MdCodec.snapshot(1, List.of(new MdCodec.Book(1, levels(2, 1, 1), List.of())));
    assertThatThrownBy(() -> MdCodec.decodeSnapshot(Arrays.copyOf(snap, snap.length - 1)))
        .isInstanceOf(ProtocolException.class);
  }

  @Test
  void depthBookAppliesAndReplaces() {
    DepthBook b = new DepthBook();
    b.apply(1, Side.BUY, 99, 5);
    b.apply(1, Side.BUY, 100, 3);
    b.apply(1, Side.SELL, 101, 2);
    assertThat(b.levels(1, Side.BUY, 0))
        .containsExactly(new DepthBook.Level(100, 3), new DepthBook.Level(99, 5));
    b.apply(1, Side.BUY, 100, 0);
    assertThat(b.levels(1, Side.BUY, 1)).containsExactly(new DepthBook.Level(99, 5));
    b.replace(2, List.of(new DepthBook.Level(5, 1)), List.of());
    assertThat(b.instruments()).containsExactly(1, 2);
  }
}
