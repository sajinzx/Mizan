package com.sajin.mizan.chain;

import com.sajin.mizan.core.ProtocolException;
import java.nio.ByteBuffer;
import java.util.Objects;

/** {@code Committed = Block || CommitCert} (§5.7): the unit written to the block log. */
public record CommittedBlock(Block block, CommitCert cert) {
  public CommittedBlock {
    Objects.requireNonNull(block, "block");
    Objects.requireNonNull(cert, "cert");
  }

  public long height() {
    return block.height();
  }

  public byte[] encode() {
    ByteBuffer b = ByteBuffer.allocate(block.encodedSize() + cert.encodedSize());
    block.write(b);
    cert.write(b);
    return b.array();
  }

  public static CommittedBlock decode(byte[] bytes) throws ProtocolException {
    ByteBuffer b = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    Block block = Block.read(b);
    CommitCert cert = CommitCert.read(b);
    if (b.hasRemaining()) {
      throw new ProtocolException("trailing bytes after commit cert");
    }
    return new CommittedBlock(block, cert);
  }
}
