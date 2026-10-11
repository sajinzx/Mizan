package com.sajin.mizan.core;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Canonical big-endian encoding of the signed command envelope, version 1 (§5.3).
 *
 * <pre>
 * 0  1  version = 1
 * 1  1  tag: 1=REGISTER 2=DEPOSIT 3=PLACE 4=CANCEL
 * 2  4  chainId
 * 6  8  accountId
 * 14 8  nonce
 * 22 …  body
 * …  64 Ed25519 signature over bytes [0, end of body)
 * </pre>
 *
 * <p>{@link #decode} throws only {@link ProtocolException} for any malformed input. Encoding
 * methods throw {@link IllegalArgumentException} for values that cannot be represented; those are
 * local programming errors, not wire input.
 */
public final class CommandCodec {
  public static final int VERSION = 1;
  public static final int TAG_REGISTER = 1;
  public static final int TAG_DEPOSIT = 2;
  public static final int TAG_PLACE = 3;
  public static final int TAG_CANCEL = 4;

  public static final int HEADER_BYTES = 22;
  public static final int SIGNATURE_BYTES = 64;
  public static final int PUBKEY_BYTES = 44;

  static final int REGISTER_BODY = 1 + PUBKEY_BYTES;
  static final int DEPOSIT_BODY = 8 + 2 + 8;
  static final int PLACE_BODY = 8 + 2 + 1 + 1 + 1 + 8 + 8;
  static final int CANCEL_BODY = 8 + 2;

  private CommandCodec() {}

  /** Bytes covered by the signature: header and body. */
  public static byte[] encodeUnsigned(int chainId, long accountId, long nonce, OrderCommand body) {
    Objects.requireNonNull(body, "body");
    ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES + bodyLength(tagOf(body)));
    b.put((byte) VERSION).put((byte) tagOf(body)).putInt(chainId).putLong(accountId).putLong(nonce);
    switch (body) {
      case Register r -> {
        if (r.pubKey().length != PUBKEY_BYTES) {
          throw new IllegalArgumentException("pubKey must be " + PUBKEY_BYTES + " bytes");
        }
        b.put((byte) PUBKEY_BYTES).put(r.pubKey());
      }
      case Deposit d -> b.putLong(d.target()).putShort(u16(d.asset())).putLong(d.amount());
      case Place p ->
          b.putLong(p.clientOrderId())
              .putShort(u16(p.instrument()))
              .put((byte) p.side().code())
              .put((byte) p.type().code())
              .put((byte) p.tif().code())
              .putLong(p.price())
              .putLong(p.qty());
      case Cancel c -> b.putLong(c.orderId()).putShort(u16(c.instrument()));
    }
    return b.array();
  }

  /** Builds a {@link SignedCommand} from its unsigned bytes and a 64-byte signature. */
  public static SignedCommand assemble(byte[] unsigned, byte[] signature) throws ProtocolException {
    if (signature.length != SIGNATURE_BYTES) {
      throw new IllegalArgumentException("signature must be " + SIGNATURE_BYTES + " bytes");
    }
    byte[] raw = Arrays.copyOf(unsigned, unsigned.length + SIGNATURE_BYTES);
    System.arraycopy(signature, 0, raw, unsigned.length, SIGNATURE_BYTES);
    return decode(raw);
  }

  /** Re-encodes a command from its fields. Equals {@code sc.raw()} for any decoded command. */
  public static byte[] encode(SignedCommand sc) {
    byte[] unsigned = encodeUnsigned(sc.chainId(), sc.accountId(), sc.nonce(), sc.body());
    if (sc.signature().length != SIGNATURE_BYTES) {
      throw new IllegalArgumentException("signature must be " + SIGNATURE_BYTES + " bytes");
    }
    byte[] raw = Arrays.copyOf(unsigned, unsigned.length + SIGNATURE_BYTES);
    System.arraycopy(sc.signature(), 0, raw, unsigned.length, SIGNATURE_BYTES);
    return raw;
  }

  /** The signed region of {@code sc.raw()}: everything except the trailing signature. */
  public static byte[] unsignedBytes(SignedCommand sc) {
    return Arrays.copyOf(sc.raw(), sc.raw().length - SIGNATURE_BYTES);
  }

  /**
   * Decodes and structurally validates an envelope. The returned command keeps a private copy of
   * {@code raw}.
   *
   * @throws ProtocolException on unknown version or tag, wrong pubkey length, out-of-range
   *     ordinals, missing or trailing bytes, or length above {@link Limits#MAX_ENVELOPE_BYTES}
   */
  public static SignedCommand decode(byte[] raw) throws ProtocolException {
    if (raw == null) {
      throw new ProtocolException("null envelope");
    }
    if (raw.length > Limits.MAX_ENVELOPE_BYTES) {
      throw new ProtocolException("envelope too large: " + raw.length);
    }
    if (raw.length < HEADER_BYTES + SIGNATURE_BYTES) {
      throw new ProtocolException("envelope too short: " + raw.length);
    }
    ByteBuffer b = ByteBuffer.wrap(raw).asReadOnlyBuffer();
    int version = Byte.toUnsignedInt(b.get());
    if (version != VERSION) {
      throw new ProtocolException("unknown version: " + version);
    }
    int tag = Byte.toUnsignedInt(b.get());
    if (tag < TAG_REGISTER || tag > TAG_CANCEL) {
      throw new ProtocolException("unknown tag: " + tag);
    }
    int expected = HEADER_BYTES + bodyLength(tag) + SIGNATURE_BYTES;
    if (raw.length != expected) {
      throw new ProtocolException(
          (raw.length < expected ? "missing" : "trailing")
              + " bytes: length "
              + raw.length
              + ", expected "
              + expected);
    }
    int chainId = b.getInt();
    long accountId = b.getLong();
    long nonce = b.getLong();
    OrderCommand body =
        switch (tag) {
          case TAG_REGISTER -> {
            int len = Byte.toUnsignedInt(b.get());
            if (len != PUBKEY_BYTES) {
              throw new ProtocolException("pubkey length must be " + PUBKEY_BYTES + ": " + len);
            }
            byte[] pk = new byte[PUBKEY_BYTES];
            b.get(pk);
            yield new Register(pk);
          }
          case TAG_DEPOSIT ->
              new Deposit(b.getLong(), Short.toUnsignedInt(b.getShort()), b.getLong());
          case TAG_PLACE -> {
            long clientOrderId = b.getLong();
            int instrument = Short.toUnsignedInt(b.getShort());
            Side side = known(Side.fromCode(Byte.toUnsignedInt(b.get())), "side");
            OrderType type = known(OrderType.fromCode(Byte.toUnsignedInt(b.get())), "type");
            TimeInForce tif = known(TimeInForce.fromCode(Byte.toUnsignedInt(b.get())), "tif");
            yield new Place(clientOrderId, instrument, side, type, tif, b.getLong(), b.getLong());
          }
          default -> new Cancel(b.getLong(), Short.toUnsignedInt(b.getShort()));
        };
    byte[] signature = new byte[SIGNATURE_BYTES];
    b.get(signature);
    return new SignedCommand(chainId, accountId, nonce, body, signature, raw.clone());
  }

  /** Total envelope length for a body, including header and signature. */
  public static int envelopeLength(OrderCommand body) {
    return HEADER_BYTES + bodyLength(tagOf(body)) + SIGNATURE_BYTES;
  }

  public static int tagOf(OrderCommand body) {
    return switch (body) {
      case Register r -> TAG_REGISTER;
      case Deposit d -> TAG_DEPOSIT;
      case Place p -> TAG_PLACE;
      case Cancel c -> TAG_CANCEL;
    };
  }

  private static int bodyLength(int tag) {
    return switch (tag) {
      case TAG_REGISTER -> REGISTER_BODY;
      case TAG_DEPOSIT -> DEPOSIT_BODY;
      case TAG_PLACE -> PLACE_BODY;
      case TAG_CANCEL -> CANCEL_BODY;
      default -> throw new IllegalArgumentException("unknown tag: " + tag);
    };
  }

  private static <E> E known(E value, String field) throws ProtocolException {
    if (value == null) {
      throw new ProtocolException(field + " code out of range");
    }
    return value;
  }

  private static short u16(int v) {
    if (v < 0 || v > 0xFFFF) {
      throw new IllegalArgumentException("value does not fit in 16 bits: " + v);
    }
    return (short) v;
  }
}
