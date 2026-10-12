package com.sajin.mizan.net;

import com.sajin.mizan.core.ProtocolException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Length-prefixed framing over a TCP stream (§5.5): {@code [len:4][type:1][payload]} with {@code
 * len = 1 + payload.length}, big-endian, {@code 1 <= len <= MAX_FRAME}.
 *
 * <p>TCP is a stream, so reads always loop until the whole frame has arrived ({@code readNBytes}).
 * A clean end of stream <em>between</em> frames is reported as {@code null}; an end of stream
 * inside a frame is an {@link EOFException}.
 */
public final class FrameCodec {
  public static final int MAX_FRAME = 1 << 20;

  // Client -> server
  public static final int ORDER = 0x01;
  public static final int QUERY_STATUS = 0x02;
  public static final int SNAPSHOT_REQ = 0x03;
  public static final int CHAIN_INFO_REQ = 0x04;
  // Server -> client
  public static final int BUSY = 0x10;
  public static final int ACCEPTED_PROVISIONAL = 0x11;
  public static final int REJECTED = 0x12;
  public static final int FINAL = 0x13;
  public static final int STATUS = 0x14;
  public static final int ERR = 0x1F;
  public static final int TRADE = 0x20;
  public static final int REVOKE = 0x21;
  public static final int SNAPSHOT = 0x22;
  public static final int CHAIN_INFO = 0x23;
  // Peer -> peer (M7+)
  public static final int PEER = 0x40;
  public static final int PEER_CHALLENGE = 0x41;
  public static final int PEER_HELLO = 0x42;

  private FrameCodec() {}

  /** One frame. The payload array is not copied. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Frame(int type, byte[] payload) {
    public Frame {
      Objects.requireNonNull(payload, "payload");
      if (type < 0 || type > 0xFF) {
        throw new IllegalArgumentException("type must fit in a byte");
      }
    }
  }

  /** Encodes a frame to bytes. */
  public static byte[] encode(int type, byte[] payload) {
    if (payload.length + 1 > MAX_FRAME) {
      throw new IllegalArgumentException("frame too large: " + payload.length);
    }
    return ByteBuffer.allocate(5 + payload.length)
        .putInt(1 + payload.length)
        .put((byte) type)
        .put(payload)
        .array();
  }

  public static void write(OutputStream out, int type, byte[] payload) throws IOException {
    out.write(encode(type, payload));
  }

  /**
   * Reads one frame.
   *
   * @return the frame, or {@code null} on a clean end of stream before any byte of a frame
   * @throws ProtocolException if the length is out of range
   * @throws EOFException if the stream ends inside a frame
   */
  public static Frame read(InputStream in) throws IOException {
    int b0 = in.read();
    if (b0 < 0) {
      return null;
    }
    byte[] rest = in.readNBytes(3);
    if (rest.length < 3) {
      throw new EOFException("stream ended inside a frame header");
    }
    int len = (b0 << 24) | ((rest[0] & 0xFF) << 16) | ((rest[1] & 0xFF) << 8) | (rest[2] & 0xFF);
    if (len < 1 || len > MAX_FRAME) {
      throw new ProtocolException("bad frame length " + len);
    }
    byte[] body = in.readNBytes(len);
    if (body.length < len) {
      throw new EOFException("stream ended inside a frame body");
    }
    byte[] payload = new byte[len - 1];
    System.arraycopy(body, 1, payload, 0, payload.length);
    return new Frame(body[0] & 0xFF, payload);
  }
}
