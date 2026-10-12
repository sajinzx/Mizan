package com.sajin.mizan.store;

import com.sajin.mizan.core.PersistenceException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The consensus safety WAL (§6.7, M7-T5): an append-only log of the validator's safety state.
 * {@link #write} appends one record and fsyncs <em>before</em> returning, and the caller sends a
 * proposal or vote only after that. {@link #read} returns the last valid record.
 *
 * <p>Records are {@code [len:4][crc32c:4][payload]}. A torn final record (a crash mid-append) is
 * ignored: its write never completed, so the message it guarded was never sent, and the previous
 * state is the correct one. Corruption <em>before</em> the last record is refused, because silently
 * forgetting a signed vote is exactly what this file prevents. When the log exceeds 1 MiB it is
 * compacted to one record (temp file + fsync + atomic rename, retried on transient Windows sharing
 * violations).
 *
 * <p>Appending instead of renaming a file per vote keeps each save to a single fsync, and avoids
 * renames that virus scanners and indexers can briefly block on Windows.
 */
public final class ConsensusWal implements AutoCloseable {
  private static final long COMPACT_AT = 1 << 20;

  private final Path file;
  private final Path tmp;
  private FileChannel ch;

  public ConsensusWal(Path file) {
    this.file = file;
    this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
  }

  /** The last stored payload, or {@code null} if nothing was ever written. */
  public synchronized byte[] read() throws PersistenceException {
    try {
      Files.deleteIfExists(tmp);
      if (!Files.exists(file)) {
        return null;
      }
      byte[] all = Files.readAllBytes(file);
      ByteBuffer b = ByteBuffer.wrap(all);
      byte[] last = null;
      while (b.remaining() >= 8) {
        int len = b.getInt();
        int crc = b.getInt();
        if (len < 0 || len > b.remaining()) {
          break; // incomplete final record: torn tail
        }
        byte[] payload = new byte[len];
        b.get(payload);
        if (BlockStore.crc32c(payload) != crc) {
          if (b.hasRemaining()) {
            throw new PersistenceException(
                "consensus WAL " + file + " is corrupt before its last record");
          }
          break; // torn tail
        }
        last = payload;
      }
      return last;
    } catch (IOException e) {
      throw e instanceof PersistenceException p
          ? p
          : new PersistenceException("cannot read " + file, e);
    }
  }

  /** Durably records {@code payload} as the current state. Returns after fsync. */
  public synchronized void write(byte[] payload) throws PersistenceException {
    try {
      if (ch == null) {
        ch =
            FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
      }
      if (ch.size() > COMPACT_AT) {
        compact(payload);
        return;
      }
      ByteBuffer rec = record(payload);
      while (rec.hasRemaining()) {
        ch.write(rec);
      }
      ch.force(true);
    } catch (IOException e) {
      throw e instanceof PersistenceException p
          ? p
          : new PersistenceException("cannot write " + file, e);
    }
  }

  private void compact(byte[] payload) throws IOException {
    ch.close();
    ch = null;
    try (FileChannel t =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer rec = record(payload);
      while (rec.hasRemaining()) {
        t.write(rec);
      }
      t.force(true);
    }
    moveWithRetry(tmp, file);
  }

  private static ByteBuffer record(byte[] payload) {
    ByteBuffer b = ByteBuffer.allocate(8 + payload.length);
    b.putInt(payload.length).putInt(BlockStore.crc32c(payload)).put(payload).flip();
    return b;
  }

  /** Atomic rename, retried briefly on Windows sharing violations (scanners, indexers). */
  static void moveWithRetry(Path from, Path to) throws IOException {
    for (int attempt = 0; ; attempt++) {
      try {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return;
      } catch (AccessDeniedException e) {
        if (attempt >= 50) {
          throw e;
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  @Override
  public synchronized void close() throws PersistenceException {
    try {
      if (ch != null) {
        ch.close();
        ch = null;
      }
    } catch (IOException e) {
      throw new PersistenceException("cannot close " + file, e);
    }
  }
}
