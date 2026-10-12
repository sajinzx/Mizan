package com.sajin.mizan.store;

import com.sajin.mizan.core.PersistenceException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * State snapshots (§6.7). A snapshot is written to a temporary file, fsynced, then atomically
 * renamed to {@code snapshot-<height>.bin}, so a crash leaves either the old set or the new file
 * complete, never a partial snapshot under the final name. The newest {@code keep} snapshots are
 * kept.
 *
 * <p>File format: {@code "MZSNAP1" || height:8 || stateRoot:32 || len:4 || crc32c:4 || state}.
 */
public final class SnapshotStore {
  private static final byte[] MAGIC = "MZSNAP1".getBytes(StandardCharsets.US_ASCII);
  private static final int HEADER = 7 + 8 + 32 + 4 + 4;

  /** A verified snapshot. Arrays are not copied. */
  @SuppressWarnings("ArrayRecordComponent")
  public record Snapshot(long height, byte[] stateRoot, byte[] state) {}

  private final Path dir;
  private final int keep;

  public SnapshotStore(Path dir, int keep) throws PersistenceException {
    this.dir = dir;
    this.keep = Math.max(1, keep);
    try {
      Files.createDirectories(dir);
      try (Stream<Path> s = Files.list(dir)) {
        for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList()) {
          Files.deleteIfExists(p); // leftovers of a crash mid-snapshot
        }
      }
    } catch (IOException e) {
      throw new PersistenceException("cannot prepare snapshot dir " + dir, e);
    }
  }

  static String name(long height) {
    return String.format("snapshot-%020d.bin", height);
  }

  public void write(long height, byte[] stateRoot, byte[] state) throws PersistenceException {
    ByteBuffer b = ByteBuffer.allocate(HEADER + state.length);
    b.put(MAGIC)
        .putLong(height)
        .put(stateRoot)
        .putInt(state.length)
        .putInt(BlockStore.crc32c(state))
        .put(state)
        .flip();
    Path tmp = dir.resolve(name(height) + ".tmp");
    Path fin = dir.resolve(name(height));
    try (FileChannel ch =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      if (FaultPoints.due(FaultPoints.MID_SNAPSHOT)) {
        b.limit(b.limit() / 2);
        while (b.hasRemaining()) {
          ch.write(b);
        }
        ch.force(true);
        FaultPoints.crash();
      }
      while (b.hasRemaining()) {
        ch.write(b);
      }
      ch.force(true);
    } catch (IOException e) {
      throw new PersistenceException("snapshot write failed at height " + height, e);
    }
    try {
      try {
        Files.move(tmp, fin, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        throw new PersistenceException("filesystem does not support atomic rename", e);
      }
      fsyncDir();
      prune();
    } catch (IOException e) {
      throw e instanceof PersistenceException p
          ? p
          : new PersistenceException("snapshot rename failed", e);
    }
  }

  private void fsyncDir() {
    try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
      d.force(true);
    } catch (IOException e) {
      // Windows cannot open a directory as a channel; NTFS journals the rename itself.
    }
  }

  private List<Path> snapshotFiles() throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      List<Path> files =
          new ArrayList<>(
              s.filter(p -> p.getFileName().toString().matches("snapshot-\\d{20}\\.bin")).toList());
      files.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
      return files; // newest first
    }
  }

  private void prune() throws IOException {
    List<Path> files = snapshotFiles();
    for (int i = keep; i < files.size(); i++) {
      Files.deleteIfExists(files.get(i));
    }
  }

  /**
   * The newest snapshot with {@code height <= maxHeight} whose checksum verifies, or {@code null}.
   */
  public Snapshot latestValid(long maxHeight) throws PersistenceException {
    try {
      for (Path p : snapshotFiles()) {
        Snapshot s = readVerified(p);
        if (s != null && s.height() <= maxHeight) {
          return s;
        }
      }
      return null;
    } catch (IOException e) {
      throw new PersistenceException("cannot list snapshots in " + dir, e);
    }
  }

  private static Snapshot readVerified(Path p) {
    try {
      byte[] all = Files.readAllBytes(p);
      if (all.length < HEADER || !Arrays.equals(Arrays.copyOf(all, 7), MAGIC)) {
        return null;
      }
      ByteBuffer b = ByteBuffer.wrap(all, 7, all.length - 7);
      long height = b.getLong();
      byte[] root = new byte[32];
      b.get(root);
      int len = b.getInt();
      int crc = b.getInt();
      if (len != b.remaining()) {
        return null;
      }
      byte[] state = new byte[len];
      b.get(state);
      return BlockStore.crc32c(state) == crc ? new Snapshot(height, root, state) : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }
}
