package com.sajin.mizan.net;

import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Typed payloads of the client protocol (§5.5). Every server message has an {@code encode()}
 * producing a full frame; {@link #decode} parses a frame back into a message and throws only {@link
 * ProtocolException} on malformed input.
 */
public final class Messages {
  private Messages() {}

  /** Error codes carried by {@code ERR}. */
  public static final int ERR_BAD_FRAME = 1;

  public static final int ERR_BAD_ENVELOPE = 2;
  public static final int ERR_UNKNOWN_TYPE = 3;
  public static final int ERR_TOO_MANY_CONNECTIONS = 4;
  public static final int ERR_TIMEOUT = 5;
  public static final int ERR_SLOW_CONSUMER = 6;
  public static final int ERR_SHUTTING_DOWN = 7;

  /** {@code REJECTED} reason code used when the gateway pre-check fails (not an engine code). */
  public static final int REASON_PRECHECK = 4;

  /** Any server-to-client message. */
  public sealed interface ServerMessage
      permits Busy, Accepted, Rejected, Final, Status, TradeMsg, Revoke, Err, Snapshot, ChainInfo {
    byte[] encode();
  }

  public record Busy(long account, long nonce) implements ServerMessage {
    @Override
    public byte[] encode() {
      return FrameCodec.encode(
          FrameCodec.BUSY, ByteBuffer.allocate(16).putLong(account).putLong(nonce).array());
    }
  }

  public record Accepted(long account, long nonce, long orderId) implements ServerMessage {
    @Override
    public byte[] encode() {
      return FrameCodec.encode(
          FrameCodec.ACCEPTED_PROVISIONAL,
          ByteBuffer.allocate(24).putLong(account).putLong(nonce).putLong(orderId).array());
    }
  }

  public record Rejected(long account, long nonce, int reasonCode, String message)
      implements ServerMessage {
    @Override
    public byte[] encode() {
      byte[] m = truncate(message);
      return FrameCodec.encode(
          FrameCodec.REJECTED,
          ByteBuffer.allocate(20 + m.length)
              .putLong(account)
              .putLong(nonce)
              .putShort((short) reasonCode)
              .putShort((short) m.length)
              .put(m)
              .array());
    }
  }

  /** {@code status}: 1 accepted, 2 rejected (business reject that is final). */
  public record Final(long account, long nonce, long height, int status, long orderId)
      implements ServerMessage {
    public static final int ACCEPTED = 1;
    public static final int REJECTED = 2;

    @Override
    public byte[] encode() {
      return FrameCodec.encode(
          FrameCodec.FINAL,
          ByteBuffer.allocate(33)
              .putLong(account)
              .putLong(nonce)
              .putLong(height)
              .put((byte) status)
              .putLong(orderId)
              .array());
    }
  }

  /** {@code state}: 0 unknown, 1 provisional, 2 rejected, 3 final. */
  public record Status(long account, long nonce, int state, long height, long orderId)
      implements ServerMessage {
    public static final int UNKNOWN = 0;
    public static final int PROVISIONAL = 1;
    public static final int REJECTED = 2;
    public static final int FINAL = 3;

    @Override
    public byte[] encode() {
      return FrameCodec.encode(
          FrameCodec.STATUS,
          ByteBuffer.allocate(33)
              .putLong(account)
              .putLong(nonce)
              .put((byte) state)
              .putLong(height)
              .putLong(orderId)
              .array());
    }
  }

  public record TradeMsg(Trade trade) implements ServerMessage {
    @Override
    public byte[] encode() {
      Trade t = trade;
      return FrameCodec.encode(
          FrameCodec.TRADE,
          ByteBuffer.allocate(8 + 2 + 8 + 8 + 8 + 8 + 1 + 8 + 8 + 8)
              .putLong(t.seq())
              .putShort((short) t.instrument())
              .putLong(t.makerOrderId())
              .putLong(t.takerOrderId())
              .putLong(t.buyAccount())
              .putLong(t.sellAccount())
              .put((byte) t.takerSide().code())
              .putLong(t.price())
              .putLong(t.qty())
              .putLong(t.buyEscrowPrice())
              .array());
    }
  }

  public record Revoke(long fromTradeSeq) implements ServerMessage {
    @Override
    public byte[] encode() {
      return FrameCodec.encode(
          FrameCodec.REVOKE, ByteBuffer.allocate(8).putLong(fromTradeSeq).array());
    }
  }

  public record Err(int code) implements ServerMessage {
    @Override
    public byte[] encode() {
      return FrameCodec.encode(
          FrameCodec.ERR, ByteBuffer.allocate(2).putShort((short) code).array());
    }
  }

  /** Market-data snapshot payload (M5); opaque to this class. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Snapshot(byte[] payload) implements ServerMessage {
    @Override
    public byte[] encode() {
      return FrameCodec.encode(FrameCodec.SNAPSHOT, payload);
    }
  }

  /**
   * The node's committed head and what it knows about each validator (M9-T6). {@code ageMillis} is
   * the time since the validator was last heard from ({@code 0} for the answering node itself,
   * {@code -1} if never); {@code height} is its last reported height ({@code -1} if unknown).
   */
  @SuppressWarnings("ArrayRecordComponent")
  public record ChainInfo(
      long height, byte[] headHash, byte[] stateRoot, List<Validator> validators)
      implements ServerMessage {
    public static final int MAX_VALIDATORS = 1_024;

    public record Validator(int index, long height, long ageMillis) {}

    public ChainInfo {
      if (headHash.length != 32 || stateRoot.length != 32) {
        throw new IllegalArgumentException("hashes must be 32 bytes");
      }
      validators = List.copyOf(validators);
      if (validators.size() > MAX_VALIDATORS) {
        throw new IllegalArgumentException("too many validators");
      }
    }

    @Override
    public byte[] encode() {
      ByteBuffer b = ByteBuffer.allocate(8 + 32 + 32 + 2 + validators.size() * 18);
      b.putLong(height).put(headHash).put(stateRoot).putShort((short) validators.size());
      for (Validator v : validators) {
        b.putShort((short) v.index()).putLong(v.height()).putLong(v.ageMillis());
      }
      return FrameCodec.encode(FrameCodec.CHAIN_INFO, b.array());
    }
  }

  // ------------------------------------------------------------------ client requests

  public static byte[] chainInfoRequest() {
    return FrameCodec.encode(FrameCodec.CHAIN_INFO_REQ, new byte[0]);
  }

  public static byte[] order(byte[] envelope) {
    return FrameCodec.encode(FrameCodec.ORDER, envelope);
  }

  public static byte[] queryStatus(long account, long nonce) {
    return FrameCodec.encode(
        FrameCodec.QUERY_STATUS, ByteBuffer.allocate(16).putLong(account).putLong(nonce).array());
  }

  /** Parses a {@code QUERY_STATUS} payload into {@code [account, nonce]}. */
  public static long[] parseQuery(byte[] payload) throws ProtocolException {
    if (payload.length != 16) {
      throw new ProtocolException("QUERY_STATUS payload must be 16 bytes");
    }
    ByteBuffer b = ByteBuffer.wrap(payload);
    return new long[] {b.getLong(), b.getLong()};
  }

  // ------------------------------------------------------------------ decode

  public static ServerMessage decode(FrameCodec.Frame f) throws ProtocolException {
    ByteBuffer b = ByteBuffer.wrap(f.payload()).asReadOnlyBuffer();
    try {
      ServerMessage m =
          switch (f.type()) {
            case FrameCodec.BUSY -> new Busy(b.getLong(), b.getLong());
            case FrameCodec.ACCEPTED_PROVISIONAL ->
                new Accepted(b.getLong(), b.getLong(), b.getLong());
            case FrameCodec.REJECTED -> {
              long a = b.getLong();
              long n = b.getLong();
              int code = Short.toUnsignedInt(b.getShort());
              byte[] msg = new byte[Short.toUnsignedInt(b.getShort())];
              b.get(msg);
              yield new Rejected(a, n, code, new String(msg, StandardCharsets.UTF_8));
            }
            case FrameCodec.FINAL ->
                new Final(
                    b.getLong(),
                    b.getLong(),
                    b.getLong(),
                    Byte.toUnsignedInt(b.get()),
                    b.getLong());
            case FrameCodec.STATUS ->
                new Status(
                    b.getLong(),
                    b.getLong(),
                    Byte.toUnsignedInt(b.get()),
                    b.getLong(),
                    b.getLong());
            case FrameCodec.TRADE -> {
              long seq = b.getLong();
              int instrument = Short.toUnsignedInt(b.getShort());
              long maker = b.getLong();
              long taker = b.getLong();
              long buy = b.getLong();
              long sell = b.getLong();
              Side side = Side.fromCode(Byte.toUnsignedInt(b.get()));
              if (side == null) {
                throw new ProtocolException("bad side");
              }
              yield new TradeMsg(
                  new Trade(
                      seq,
                      instrument,
                      maker,
                      taker,
                      buy,
                      sell,
                      side,
                      b.getLong(),
                      b.getLong(),
                      b.getLong()));
            }
            case FrameCodec.REVOKE -> new Revoke(b.getLong());
            case FrameCodec.ERR -> new Err(Short.toUnsignedInt(b.getShort()));
            case FrameCodec.SNAPSHOT -> {
              byte[] p = new byte[b.remaining()];
              b.get(p);
              yield new Snapshot(p);
            }
            case FrameCodec.CHAIN_INFO -> {
              long height = b.getLong();
              byte[] head = new byte[32];
              b.get(head);
              byte[] root = new byte[32];
              b.get(root);
              int n = Short.toUnsignedInt(b.getShort());
              if (n > ChainInfo.MAX_VALIDATORS) {
                throw new ProtocolException("too many validators: " + n);
              }
              List<ChainInfo.Validator> vs = new java.util.ArrayList<>(n);
              for (int i = 0; i < n; i++) {
                vs.add(
                    new ChainInfo.Validator(
                        Short.toUnsignedInt(b.getShort()), b.getLong(), b.getLong()));
              }
              yield new ChainInfo(height, head, root, vs);
            }
            default -> throw new ProtocolException("unknown server message type " + f.type());
          };
      if (b.hasRemaining()) {
        throw new ProtocolException("trailing bytes in message type " + f.type());
      }
      return m;
    } catch (BufferUnderflowException e) {
      throw new ProtocolException("truncated message type " + f.type());
    }
  }

  private static byte[] truncate(String s) {
    byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    if (b.length <= 512) {
      return b;
    }
    byte[] t = new byte[512];
    System.arraycopy(b, 0, t, 0, 512);
    return t;
  }
}
