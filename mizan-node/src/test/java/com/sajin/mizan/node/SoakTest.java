package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.sim.LoadGenerator;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M4 gate: a long soak under continuous load. Heap after GC must stay flat and no threads may leak.
 * Duration from {@code -Dmizan.soakSeconds} (default 600 = 10 minutes). Run with {@code ./gradlew
 * :mizan-node:nightly --tests '*SoakTest'}.
 */
@Tag("slow")
class SoakTest {

  static long usedHeapAfterGc(MemoryMXBean mem) throws InterruptedException {
    for (int i = 0; i < 3; i++) {
      System.gc();
      Thread.sleep(100);
    }
    return mem.getHeapMemoryUsage().getUsed();
  }

  @Test
  void tenMinuteSoakHasFlatHeapAndNoLeakedThreads() throws Exception {
    long seconds = Long.getLong("mizan.soakSeconds", 600);
    MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
    int threadsBefore = Thread.activeCount();
    Node node = NodeIntegrationTest.start();
    List<Long> heap = new ArrayList<>();
    long rounds = 0;
    long orders = 0;
    try {
      long end = System.nanoTime() + seconds * 1_000_000_000L;
      long nextSample = 0;
      long account = 10_000;
      while (System.nanoTime() < end) {
        var p = LoadGenerator.Params.of("127.0.0.1", node.gatewayPort(), 20, 200, account, rounds);
        LoadGenerator.setup(p);
        LoadGenerator.Result r = LoadGenerator.run(p);
        assertThat(r.errors()).isZero();
        assertThat(r.activeThreadsAfterJoin()).isZero();
        orders += r.sent();
        account += 20;
        rounds++;
        if (System.nanoTime() >= nextSample) {
          heap.add(usedHeapAfterGc(mem));
          nextSample = System.nanoTime() + 30_000_000_000L;
        }
      }
      heap.add(usedHeapAfterGc(mem));
    } finally {
      node.close();
    }
    Thread.sleep(2_000); // let virtual and daemon threads finish
    int threadsAfter = Thread.activeCount();
    System.out.printf(
        "soak: %ds, %d rounds, %d orders, heap after GC (MiB) %s, threads before=%d after=%d%n",
        seconds,
        rounds,
        orders,
        heap.stream().map(b -> b / (1024 * 1024)).toList(),
        threadsBefore,
        threadsAfter);

    int third = Math.max(1, heap.size() / 3);
    double early =
        heap.subList(0, third).stream().mapToLong(Long::longValue).average().orElseThrow();
    double late =
        heap.subList(heap.size() - third, heap.size()).stream()
            .mapToLong(Long::longValue)
            .average()
            .orElseThrow();
    // Retained state grows a little (accounts, resting orders, result cache entries); a leak would
    // grow without bound. Allow 64 MiB of legitimate growth.
    assertThat(late).isLessThan(early + 64.0 * 1024 * 1024);
    assertThat(threadsAfter).isLessThanOrEqualTo(threadsBefore + 2);
  }
}
