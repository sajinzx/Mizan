package com.sajin.mizan.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M8-T5: heartbeats propagate heights even with 50% packet loss. */
@Timeout(30)
class UdpHeartbeatTest {

  @Test
  void heightsPropagateUnderHalfLoss() throws Exception {
    int n = 3;
    List<InetSocketAddress> peers = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      try (DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
        peers.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), s.getLocalPort()));
      }
    }
    AtomicLong[] heights = new AtomicLong[n];
    List<ConcurrentHashMap<Integer, Long>> seen = new ArrayList<>();
    List<UdpHeartbeat> beats = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      heights[i] = new AtomicLong(10L * (i + 1));
      ConcurrentHashMap<Integer, Long> view = new ConcurrentHashMap<>();
      seen.add(view);
      AtomicLong h = heights[i];
      UdpHeartbeat b = new UdpHeartbeat(i, peers, h::get, () -> new byte[32], view::put, 50, 0.5);
      b.start();
      beats.add(b);
    }
    try {
      heights[2].set(99);
      long deadline = System.nanoTime() + 10_000_000_000L;
      // Wait for everything asserted below: with 50% loss any one heartbeat may still be missing.
      while (System.nanoTime() < deadline
          && !(Long.valueOf(99).equals(seen.get(0).get(2))
              && Long.valueOf(99).equals(seen.get(1).get(2))
              && seen.get(0).containsKey(1)
              && seen.get(1).containsKey(0)
              && seen.get(2).containsKey(0)
              && seen.get(2).containsKey(1))) {
        Thread.sleep(20);
      }
      assertThat(seen.get(0)).containsEntry(2, 99L).containsKey(1);
      assertThat(seen.get(1)).containsEntry(2, 99L).containsKey(0);
      assertThat(seen.get(2)).containsKeys(0, 1).doesNotContainKey(2);
    } finally {
      beats.forEach(UdpHeartbeat::close);
    }
  }
}
