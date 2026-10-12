package com.sajin.mizan.net.md;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M9-T3: a SUBSCRIBE flood from many source ports cannot grow the subscriber map unboundedly. */
@Timeout(30)
class SubscriberCapTest {

  @Test
  void subscriptionsAreCapped() throws Exception {
    List<DatagramSocket> clients = new ArrayList<>();
    try (MarketDataService md = new MarketDataService(1 << 10, 0, 50, List.of(1), 3)) {
      InetSocketAddress to = new InetSocketAddress(InetAddress.getLoopbackAddress(), md.udpPort());
      for (int i = 0; i < 10; i++) {
        DatagramSocket c = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        clients.add(c);
        c.send(new DatagramPacket(new byte[] {MdCodec.SUBSCRIBE}, 1, to));
      }
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (md.subscriberCount() < 3 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      Thread.sleep(200); // let the remaining datagrams arrive
      assertThat(md.subscriberCount()).isEqualTo(3);
      // An existing subscriber can still refresh.
      clients.get(0).send(new DatagramPacket(new byte[] {MdCodec.SUBSCRIBE}, 1, to));
      Thread.sleep(100);
      assertThat(md.subscriberCount()).isEqualTo(3);
    } finally {
      clients.forEach(DatagramSocket::close);
    }
  }
}
