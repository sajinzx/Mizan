package com.sajin.mizan.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.PersistenceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M6-T3/T4: block log integrity and atomic snapshots. */
class BlockStoreTest {
  @TempDir Path dir;

  static byte[] rec(int i) {
    byte[] b = new byte[20 + i];
    Arrays.fill(b, (byte) i);
    return b;
  }

  Path writeLog(int n) throws PersistenceException {
    Path f = dir.resolve("blocks.log");
    try (BlockStore s = BlockStore.open(f)) {
      for (int i = 0; i < n; i++) {
        s.append(rec(i));
      }
    }
    return f;
  }

  @Test
  void appendAndReopen() throws Exception {
    Path f = writeLog(5);
    try (BlockStore s = BlockStore.open(f)) {
      assertThat(s.size()).isEqualTo(5);
      assertThat(s.truncatedBytes()).isZero();
      for (int i = 0; i < 5; i++) {
        assertThat(s.read(i)).isEqualTo(rec(i));
      }
      s.append(rec(5));
    }
    try (BlockStore s = BlockStore.open(f)) {
      assertThat(s.size()).isEqualTo(6);
    }
  }

  @Test
  void tornTailIsTruncatedAtEveryCutPoint() throws Exception {
    Path f = writeLog(3);
    byte[] full = Files.readAllBytes(f);
    long fourStart = full.length;
    try (BlockStore s = BlockStore.open(f)) {
      s.append(rec(3));
    }
    byte[] four = Files.readAllBytes(f);
    for (long cut = fourStart + 1; cut < four.length; cut++) {
      Files.write(f, Arrays.copyOf(four, (int) cut));
      try (BlockStore s = BlockStore.open(f)) {
        assertThat(s.size()).as("cut at %d", cut).isEqualTo(3);
        assertThat(s.truncatedBytes()).isEqualTo(cut - fourStart);
      }
      assertThat(Files.size(f)).isEqualTo(fourStart);
    }
  }

  @Test
  void corruptLastRecordIsTreatedAsTornTail() throws Exception {
    Path f = writeLog(3);
    byte[] b = Files.readAllBytes(f);
    b[b.length - 1] ^= 1;
    Files.write(f, b);
    try (BlockStore s = BlockStore.open(f)) {
      assertThat(s.size()).isEqualTo(2);
    }
  }

  @Test
  void midLogCorruptionIsRefused() throws Exception {
    Path f = writeLog(4);
    byte[] b = Files.readAllBytes(f);
    for (int flipAt : new int[] {9, 33, 40}) { // record 1 body, record 2 CRC and body; data follows
      byte[] m = b.clone();
      m[flipAt] ^= 0x10;
      Files.write(f, m);
      assertThatThrownBy(() -> BlockStore.open(f))
          .isInstanceOf(PersistenceException.class)
          .hasMessageContaining("refusing to start");
    }
    byte[] badLen = b.clone();
    badLen[0] = (byte) 0x7F; // first record claims a huge length, data follows
    Files.write(f, badLen);
    assertThatThrownBy(() -> BlockStore.open(f)).hasMessageContaining("bad record length");
  }

  @Test
  void snapshotsAreAtomicVerifiedAndPruned() throws Exception {
    SnapshotStore s = new SnapshotStore(dir.resolve("snap"), 2);
    byte[] root = new byte[32];
    assertThat(s.latestValid(Long.MAX_VALUE)).isNull();
    for (long h = 10; h <= 40; h += 10) {
      root[0] = (byte) h;
      s.write(h, root, rec((int) h));
    }
    try (var files = Files.list(dir.resolve("snap"))) {
      assertThat(files.count()).isEqualTo(2);
    }
    SnapshotStore.Snapshot latest = s.latestValid(Long.MAX_VALUE);
    assertThat(latest.height()).isEqualTo(40);
    assertThat(latest.state()).isEqualTo(rec(40));
    assertThat(latest.stateRoot()[0]).isEqualTo((byte) 40);
    assertThat(s.latestValid(39).height()).isEqualTo(30);
    assertThat(s.latestValid(29)).isNull();

    // A corrupted newest snapshot is skipped; a stray temp file is cleaned up on open.
    Path newest = dir.resolve("snap").resolve(SnapshotStore.name(40));
    byte[] b = Files.readAllBytes(newest);
    b[b.length - 1] ^= 1;
    Files.write(newest, b);
    Files.write(dir.resolve("snap").resolve(SnapshotStore.name(50) + ".tmp"), new byte[] {1, 2});
    SnapshotStore reopened = new SnapshotStore(dir.resolve("snap"), 2);
    assertThat(reopened.latestValid(Long.MAX_VALUE).height()).isEqualTo(30);
    assertThat(Files.exists(dir.resolve("snap").resolve(SnapshotStore.name(50) + ".tmp")))
        .isFalse();
  }

  @Test
  void faultPointsAreInertWithoutTheProperty() throws IOException {
    assertThat(FaultPoints.due(FaultPoints.BEFORE_APPEND)).isFalse();
    FaultPoints.hit(FaultPoints.MID_APPEND); // does not halt
  }
}
