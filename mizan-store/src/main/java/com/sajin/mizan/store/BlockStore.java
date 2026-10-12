package com.sajin.mizan.store;

import com.sajin.mizan.core.PersistenceException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Append-only block log, the write-ahead log of the node (ADR 0002). Each record is {@code
 * [len:4][crc32c:4][payload]}; record {@code i} holds height {@code i + 1}. {@link #append} returns
 * only after {@code FileChannel.force(true)}.
 *
 * <p>On open the whole log is scanned:
 *
 * <ul>
 *   <li>a <b>torn tail</b> (an incomplete final record, or a final record whose CRC fails) is the
 *       expected result of a crash mid-append, and is truncated;
 *   <li><b>corruption followed by more data</b> cannot come from a crash and makes open fail with a
 *       clear error rather than silently losing committed blocks.
 * </ul>
 *
 * <p>Not thread-safe: owned by the consensus thread.
 */
public final class BlockStore implements AutoCloseable {
  public static final int MAX_RECORD = 64 << 20;

  private final Path file;
  private final FileChannel ch;
  private final List<Long> offsets = new ArrayList<>();
  private long end;
  private final long truncatedBytes;
  private final boolean readOnly;

  private BlockStore(Path file, FileChannel ch, long truncatedBytes, boolean readOnly) {
    this.file = file;
    this.ch = ch;
    this.truncatedBytes = truncatedBytes;
    this.readOnly = readOnly;
  }

  public static BlockStore open(Path file) throws PersistenceException {
    return open(file, false);
  }

  /**
   * Opens an existing log without modifying it (for {@code mizan verify-chain}): a torn tail is
   * reported by {@link #truncatedBytes()} and skipped, not truncated; {@link #append} is refused.
   */
  public static BlockStore openReadOnly(Path file) throws PersistenceException {
    return open(file, true);
  }

  private static BlockStore open(Path file, boolean readOnly) throws PersistenceException {
    try {
      FileChannel ch =
          readOnly
              ? FileChannel.open(file, StandardOpenOption.READ)
              : FileChannel.open(
                  file,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE);
      long size = ch.size();
      List<Long> offsets = new ArrayList<>();
      long pos = 0;
      ByteBuffer hdr = ByteBuffer.allocate(8);
      while (pos < size) {
        if (size - pos < 8) {
          break; // torn header
        }
        hdr.clear();
        readFully(ch, hdr, pos);
        hdr.flip();
        int len = hdr.getInt();
        int crc = hdr.getInt();
        if (len < 0 || len > MAX_RECORD) {
          if (pos + 8 >= size) {
            break;
          }
          throw new PersistenceException(
              "block log " + file + " corrupt at offset " + pos + ": bad record length " + len);
        }
        if (pos + 8 + len > size) {
          break; // torn body
        }
        ByteBuffer body = ByteBuffer.allocate(len);
        readFully(ch, body, pos + 8);
        if (crc32c(body.array()) != crc) {
          if (pos + 8 + len == size) {
            break; // last record partially persisted: torn tail
          }
          throw new PersistenceException(
              "block log "
                  + file
                  + " corrupt at offset "
                  + pos
                  + " (record "
                  + (offsets.size() + 1)
                  + "): CRC mismatch with "
                  + (size - pos - 8 - len)
                  + " bytes after it; refusing to start");
        }
        offsets.add(pos);
        pos += 8 + len;
      }
      long truncated = size - pos;
      if (truncated > 0 && !readOnly) {
        ch.truncate(pos);
        ch.force(true);
      }
      BlockStore s = new BlockStore(file, ch, truncated, readOnly);
      s.offsets.addAll(offsets);
      s.end = pos;
      return s;
    } catch (IOException e) {
      throw e instanceof PersistenceException p
          ? p
          : new PersistenceException("cannot open " + file, e);
    }
  }

  /** Bytes of a torn tail found when the log was opened (removed unless read-only). */
  public long truncatedBytes() {
    return truncatedBytes;
  }

  /** Number of records (= committed height). */
  public int size() {
    return offsets.size();
  }

  public byte[] read(int index) throws PersistenceException {
    try {
      long off = offsets.get(index);
      ByteBuffer hdr = ByteBuffer.allocate(8);
      readFully(ch, hdr, off);
      hdr.flip();
      ByteBuffer body = ByteBuffer.allocate(hdr.getInt());
      readFully(ch, body, off + 8);
      return body.array();
    } catch (IOException e) {
      throw new PersistenceException("read failed at record " + index, e);
    }
  }

  /** Appends one record and fsyncs before returning. */
  public void append(byte[] payload) throws PersistenceException {
    if (readOnly) {
      throw new PersistenceException("block log " + file + " is open read-only");
    }
    if (payload.length > MAX_RECORD) {
      throw new PersistenceException("record too large: " + payload.length);
    }
    FaultPoints.hit(FaultPoints.BEFORE_APPEND);
    ByteBuffer rec = ByteBuffer.allocate(8 + payload.length);
    rec.putInt(payload.length).putInt(crc32c(payload)).put(payload).flip();
    try {
      if (FaultPoints.due(FaultPoints.MID_APPEND)) {
        rec.limit(rec.limit() / 2 + 1); // persist only part of the record, then die
        writeFully(ch, rec, end);
        ch.force(true);
        FaultPoints.crash();
      }
      writeFully(ch, rec, end);
      ch.force(true);
    } catch (IOException e) {
      throw new PersistenceException("append failed on " + file, e);
    }
    offsets.add(end);
    end += 8 + payload.length;
  }

  static int crc32c(byte[] data) {
    CRC32C c = new CRC32C();
    c.update(data);
    return (int) c.getValue();
  }

  private static void readFully(FileChannel ch, ByteBuffer b, long pos) throws IOException {
    while (b.hasRemaining()) {
      int n = ch.read(b, pos + b.position());
      if (n < 0) {
        throw new IOException("unexpected end of file");
      }
    }
  }

  private static void writeFully(FileChannel ch, ByteBuffer b, long pos) throws IOException {
    long p = pos;
    while (b.hasRemaining()) {
      p += ch.write(b, p);
    }
  }

  @Override
  public void close() throws PersistenceException {
    try {
      ch.close();
    } catch (IOException e) {
      throw new PersistenceException("close failed", e);
    }
  }
}
