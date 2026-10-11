package com.sajin.mizan.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.Trade;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** M3-T2: the audit tap must never block the engine (defect 5). */
class AuditTapTest {

  static Trade trade(long seq) {
    return new Trade(seq, 1, seq, seq + 1, 2, 3, Side.BUY, 100, 1, 100);
  }

  @Test
  void deliversTradesInOrder() throws Exception {
    List<String> lines = new CopyOnWriteArrayList<>();
    try (AuditTap tap = new AuditTap(1024, lines::add)) {
      for (long s = 1; s <= 500; s++) {
        tap.onTrade(trade(s));
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (lines.size() < 500 && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertThat(tap.droppedCount()).isZero();
    }
    assertThat(lines).hasSize(500);
    assertThat(lines.get(0)).startsWith("TRADE seq=1 ").contains("px=100");
    assertThat(lines.get(499)).startsWith("TRADE seq=500 ");
  }

  /**
   * Defect 5: with the pipe reader completely stalled, the engine thread can still emit trades at
   * full speed. Excess trades are dropped and counted instead of blocking.
   */
  @Test
  void stalledReaderNeverBlocksEngine() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AuditTap tap =
        new AuditTap(
            64,
            line -> {
              try {
                release.await(); // the reader never makes progress until released
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    int n = 200_000;
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          for (long s = 1; s <= n; s++) {
            tap.onTrade(trade(s));
          }
        });
    // The pipe (4 KiB) and the queue (64) are full; nearly everything else was dropped.
    assertThat(tap.droppedCount()).isGreaterThan(n - 1_000L);
    assertThat(tap.droppedCount() + tap.writtenCount()).isLessThanOrEqualTo(n);
    release.countDown();
    tap.close();
  }

  @Test
  void dropCounterIncrementsWhenQueueIsFull() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try (AuditTap tap = new AuditTap(4, line -> awaitQuietly(release))) {
      for (long s = 1; s <= 5_000; s++) {
        tap.onTrade(trade(s));
      }
      assertThat(tap.droppedCount()).isPositive();
      release.countDown();
    }
  }

  private static void awaitQuietly(CountDownLatch l) {
    try {
      l.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
