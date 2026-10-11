package com.sajin.mizan.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajin.mizan.core.Side;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.TimeInForce;
import com.sajin.mizan.testkit.SignedCommands;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M3-T3. */
class MempoolTest {

  @Test
  void fifoCapacityAndDuplicates() throws InterruptedException {
    Mempool<Integer> m = new Mempool<>(3, i -> i % 100);
    assertThat(m.add(1)).isEqualTo(Mempool.AddResult.ADDED);
    assertThat(m.add(2)).isEqualTo(Mempool.AddResult.ADDED);
    assertThat(m.add(101)).isEqualTo(Mempool.AddResult.DUPLICATE); // same key as 1
    assertThat(m.add(3)).isEqualTo(Mempool.AddResult.ADDED);
    assertThat(m.add(4)).isEqualTo(Mempool.AddResult.FULL);
    assertThat(m.size()).isEqualTo(3);
    assertThat(m.drain(2, 0)).containsExactly(1, 2);
    assertThat(m.add(101)).as("key free again after drain").isEqualTo(Mempool.AddResult.ADDED);
    assertThat(m.drain(10, 0)).containsExactly(3, 101);
    assertThatThrownBy(() -> m.drain(0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Mempool<Integer>(0, i -> i))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void commandsAreKeyedByAccountAndNonce() throws InterruptedException {
    Mempool<SignedCommand> m = Mempool.forCommands(10);
    SignedCommand a = SignedCommands.limit(2, 1, 1, Side.BUY, 100, 1, TimeInForce.GTC);
    SignedCommand sameKey = SignedCommands.limit(2, 1, 1, Side.SELL, 101, 2, TimeInForce.IOC);
    SignedCommand other = SignedCommands.limit(3, 1, 1, Side.BUY, 100, 1, TimeInForce.GTC);
    assertThat(m.add(a)).isEqualTo(Mempool.AddResult.ADDED);
    assertThat(m.add(sameKey)).isEqualTo(Mempool.AddResult.DUPLICATE);
    assertThat(m.add(other)).isEqualTo(Mempool.AddResult.ADDED);
    assertThat(m.drain(5, 0)).containsExactly(a, other);
  }

  @Test
  @Timeout(5)
  void drainTimesOutWhenEmpty() throws InterruptedException {
    Mempool<Integer> m = new Mempool<>(4, i -> i);
    long start = System.nanoTime();
    assertThat(m.drain(1, 50)).isEmpty();
    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(45));
  }

  @Test
  @Timeout(5)
  void drainWakesUpOnAdd() throws Exception {
    Mempool<Integer> m = new Mempool<>(4, i -> i);
    try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
      Future<List<Integer>> got = pool.submit(() -> m.drain(5, 10_000));
      Thread.sleep(50);
      assertThat(got.isDone()).isFalse();
      m.add(7);
      assertThat(got.get(2, TimeUnit.SECONDS)).containsExactly(7);
    }
  }

  @Test
  @Timeout(30)
  void concurrentAddAndDrainLoseNothing() throws Exception {
    Mempool<Integer> m = new Mempool<>(64, i -> i);
    int producers = 4;
    int each = 20_000;
    AtomicBoolean producing = new AtomicBoolean(true);
    List<Integer> drained = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(producers + 1)) {
      Future<?> consumer =
          pool.submit(
              () -> {
                while (producing.get() || m.size() > 0) {
                  drained.addAll(m.drain(16, 5));
                }
                return null;
              });
      List<Future<?>> ps = new ArrayList<>();
      for (int p = 0; p < producers; p++) {
        int base = p * each;
        ps.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < each; i++) {
                    while (m.add(base + i) == Mempool.AddResult.FULL) {
                      Thread.onSpinWait();
                    }
                  }
                }));
      }
      for (Future<?> f : ps) {
        f.get();
      }
      producing.set(false);
      consumer.get();
    }
    assertThat(drained).hasSize(producers * each);
    Set<Integer> unique = new HashSet<>(drained);
    assertThat(unique).hasSize(producers * each);
  }
}
