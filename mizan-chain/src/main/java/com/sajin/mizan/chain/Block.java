package com.sajin.mizan.chain;

import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.crypto.SimpleMerkle;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * {@code Block = header || count:4 || (len:4 || raw)*} (§5.7). {@code txRoot} is the {@link
 * SimpleMerkle#rootOfList} over each command's raw envelope, in order.
 *
 * <p>Immutable. The block hash is computed once and cached, because consensus looks it up many
 * times per message.
 */
public final class Block {
  /** Hard cap on commands per block, independent of genesis tuning. */
  public static final int MAX_COMMANDS = 100_000;

  private final BlockHeader header;
  private final List<SignedCommand> commands;
  private byte[] hash;

  public Block(BlockHeader header, List<SignedCommand> commands) {
    this.header = Objects.requireNonNull(header, "header");
    this.commands = List.copyOf(commands);
  }

  public BlockHeader header() {
    return header;
  }

  public List<SignedCommand> commands() {
    return commands;
  }

  public static byte[] txRoot(List<SignedCommand> commands) {
    List<byte[]> raws = new ArrayList<>(commands.size());
    commands.forEach(c -> raws.add(c.raw()));
    return SimpleMerkle.rootOfList(raws);
  }

  /** Whether the header's {@code txRoot} matches the commands. */
  public boolean txRootMatches() {
    return Arrays.equals(header.txRoot(), txRoot(commands));
  }

  /** {@code SHA256(header)}. Returns a copy of the cached value. */
  public byte[] hash() {
    byte[] h = hash;
    if (h == null) {
      h = header.hash();
      hash = h;
    }
    return h.clone();
  }

  public long height() {
    return header.height();
  }

  public byte[] encode() {
    ByteBuffer b = ByteBuffer.allocate(encodedSize());
    write(b);
    return b.array();
  }

  void write(ByteBuffer b) {
    b.put(header.encode()).putInt(commands.size());
    for (SignedCommand c : commands) {
      b.putInt(c.raw().length).put(c.raw());
    }
  }

  int encodedSize() {
    int size = BlockHeader.BYTES + 4;
    for (SignedCommand c : commands) {
      size += 4 + c.raw().length;
    }
    return size;
  }

  static Block read(ByteBuffer b) throws ProtocolException {
    BlockHeader h = BlockHeader.read(b);
    try {
      int n = b.getInt();
      if (n < 0 || n > MAX_COMMANDS) {
        throw new ProtocolException("bad command count " + n);
      }
      List<SignedCommand> cmds = new ArrayList<>(Math.min(n, 1024));
      for (int i = 0; i < n; i++) {
        int len = b.getInt();
        if (len < 0 || len > b.remaining()) {
          throw new ProtocolException("bad command length " + len);
        }
        byte[] raw = new byte[len];
        b.get(raw);
        cmds.add(CommandCodec.decode(raw));
      }
      return new Block(h, cmds);
    } catch (BufferUnderflowException e) {
      throw new ProtocolException("truncated block");
    }
  }

  public static Block decode(byte[] bytes) throws ProtocolException {
    ByteBuffer b = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    Block block = read(b);
    if (b.hasRemaining()) {
      throw new ProtocolException("trailing bytes after block");
    }
    return block;
  }

  /** Equal iff the encodings are equal. */
  @Override
  public boolean equals(Object o) {
    return o instanceof Block other
        && Arrays.equals(hash(), other.hash())
        && Arrays.equals(encode(), other.encode());
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(hash());
  }

  @Override
  public String toString() {
    return "Block[" + header + ", " + commands.size() + " commands]";
  }
}
