package com.sajin.mizan.net.md;

import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Side;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * UDP market-data packets (§6.6, ADR 0012). Big-endian, at most {@value #MAX_PACKET} bytes:
 *
 * <pre>
 * DEPTH     [type=1][seq:8][instrument:2][nBids:2][nAsks:2] (price:8 qty:8)*  — full top-35 state
 * REVOKE    [type=2][seq:8][fromTradeSeq:8]
 * HEARTBEAT [type=3][seq:8]                                                  — latest published seq
 * TRADE     [type=4][seq:8][instrument:2][tradeSeq:8][takerSide:1][price:8][qty:8]
 * SUBSCRIBE [type=9]                                                         — subscriber → publisher
 * </pre>
 *
 * <p>TCP {@code SNAPSHOT} payload: {@code [seq:8][n:2] ([instrument:2][nBids:4][nAsks:4] levels)*}
 * with every level, not just the top 35.
 */
public final class MdCodec {
  public static final int DEPTH = 1;
  public static final int REVOKE = 2;
  public static final int HEARTBEAT = 3;
  public static final int TRADE = 4;
  public static final int SUBSCRIBE = 9;
  public static final int MAX_LEVELS = 35;
  public static final int MAX_PACKET = 1_200;

  private MdCodec() {}

  /** A decoded packet. */
  public sealed interface Packet {
    long seq();
  }

  public record Depth(
      long seq, int instrument, List<DepthBook.Level> bids, List<DepthBook.Level> asks)
      implements Packet {}

  public record Revoke(long seq, long fromTradeSeq) implements Packet {}

  public record Heartbeat(long seq) implements Packet {}

  public record TradePrint(
      long seq, int instrument, long tradeSeq, Side takerSide, long price, long qty)
      implements Packet {}

  /** A full snapshot at {@code seq}. */
  public record Snapshot(long seq, List<Book> books) {}

  /** One instrument's full depth. */
  public record Book(int instrument, List<DepthBook.Level> bids, List<DepthBook.Level> asks) {}

  public static byte[] depth(
      long seq, int instrument, List<DepthBook.Level> bids, List<DepthBook.Level> asks) {
    int nb = Math.min(bids.size(), MAX_LEVELS);
    int na = Math.min(asks.size(), MAX_LEVELS);
    ByteBuffer b = ByteBuffer.allocate(15 + 16 * (nb + na));
    b.put((byte) DEPTH)
        .putLong(seq)
        .putShort((short) instrument)
        .putShort((short) nb)
        .putShort((short) na);
    for (int i = 0; i < nb; i++) {
      b.putLong(bids.get(i).price()).putLong(bids.get(i).qty());
    }
    for (int i = 0; i < na; i++) {
      b.putLong(asks.get(i).price()).putLong(asks.get(i).qty());
    }
    return b.array();
  }

  public static byte[] revoke(long seq, long fromTradeSeq) {
    return ByteBuffer.allocate(17).put((byte) REVOKE).putLong(seq).putLong(fromTradeSeq).array();
  }

  public static byte[] heartbeat(long seq) {
    return ByteBuffer.allocate(9).put((byte) HEARTBEAT).putLong(seq).array();
  }

  public static byte[] trade(
      long seq, int instrument, long tradeSeq, Side side, long price, long qty) {
    return ByteBuffer.allocate(36)
        .put((byte) TRADE)
        .putLong(seq)
        .putShort((short) instrument)
        .putLong(tradeSeq)
        .put((byte) side.code())
        .putLong(price)
        .putLong(qty)
        .array();
  }

  public static byte[] subscribe() {
    return new byte[] {SUBSCRIBE};
  }

  public static Packet decode(byte[] data, int length) throws ProtocolException {
    ByteBuffer b = ByteBuffer.wrap(data, 0, length).asReadOnlyBuffer();
    try {
      int type = Byte.toUnsignedInt(b.get());
      Packet p =
          switch (type) {
            case DEPTH -> {
              long seq = b.getLong();
              int instrument = Short.toUnsignedInt(b.getShort());
              int nb = Short.toUnsignedInt(b.getShort());
              int na = Short.toUnsignedInt(b.getShort());
              if (nb > MAX_LEVELS || na > MAX_LEVELS) {
                throw new ProtocolException("too many levels");
              }
              yield new Depth(seq, instrument, levels(b, nb), levels(b, na));
            }
            case REVOKE -> new Revoke(b.getLong(), b.getLong());
            case HEARTBEAT -> new Heartbeat(b.getLong());
            case TRADE -> {
              long seq = b.getLong();
              int instrument = Short.toUnsignedInt(b.getShort());
              long tradeSeq = b.getLong();
              Side side = Side.fromCode(Byte.toUnsignedInt(b.get()));
              if (side == null) {
                throw new ProtocolException("bad side");
              }
              yield new TradePrint(seq, instrument, tradeSeq, side, b.getLong(), b.getLong());
            }
            default -> throw new ProtocolException("unknown packet type " + type);
          };
      if (b.hasRemaining()) {
        throw new ProtocolException("trailing bytes");
      }
      return p;
    } catch (BufferUnderflowException e) {
      throw new ProtocolException("truncated packet");
    }
  }

  private static List<DepthBook.Level> levels(ByteBuffer b, int n) {
    List<DepthBook.Level> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(new DepthBook.Level(b.getLong(), b.getLong()));
    }
    return out;
  }

  public static byte[] snapshot(long seq, List<Book> books) {
    int size = 10;
    for (Book k : books) {
      size += 10 + 16 * (k.bids().size() + k.asks().size());
    }
    ByteBuffer b = ByteBuffer.allocate(size);
    b.putLong(seq).putShort((short) books.size());
    for (Book k : books) {
      b.putShort((short) k.instrument()).putInt(k.bids().size()).putInt(k.asks().size());
      k.bids().forEach(l -> b.putLong(l.price()).putLong(l.qty()));
      k.asks().forEach(l -> b.putLong(l.price()).putLong(l.qty()));
    }
    return b.array();
  }

  public static Snapshot decodeSnapshot(byte[] payload) throws ProtocolException {
    ByteBuffer b = ByteBuffer.wrap(payload).asReadOnlyBuffer();
    try {
      long seq = b.getLong();
      int n = Short.toUnsignedInt(b.getShort());
      List<Book> books = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        int instrument = Short.toUnsignedInt(b.getShort());
        int nb = b.getInt();
        int na = b.getInt();
        if (nb < 0 || na < 0 || (long) (nb + na) * 16 > b.remaining()) {
          throw new ProtocolException("bad level counts");
        }
        books.add(new Book(instrument, levels(b, nb), levels(b, na)));
      }
      if (b.hasRemaining()) {
        throw new ProtocolException("trailing bytes");
      }
      return new Snapshot(seq, books);
    } catch (BufferUnderflowException e) {
      throw new ProtocolException("truncated snapshot");
    }
  }
}
