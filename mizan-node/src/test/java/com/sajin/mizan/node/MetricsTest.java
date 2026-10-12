package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajin.mizan.core.Metrics;
import com.sajin.mizan.sim.LoadGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** M9-T1: counters, histograms and gauges, scraped over HTTP from a live node. */
@Timeout(120)
class MetricsTest {

  @Test
  void renderGivesCountsQuantilesAndGauges() {
    NodeMetrics m = new NodeMetrics();
    Metrics.Counter c = m.counter("things");
    c.inc();
    c.add(4);
    Metrics.Timer t = m.timer("lat");
    for (int i = 1; i <= 1_000; i++) {
      t.recordNanos(i * 1_000L); // 1..1000 µs
    }
    m.gauge("depth", () -> 42);
    m.gauge(
        "broken",
        () -> {
          throw new IllegalStateException();
        });
    Map<String, Long> v = parse(m.render());
    assertThat(v)
        .containsEntry("mizan_things_total", 5L)
        .containsEntry("mizan_depth", 42L)
        .containsEntry("mizan_broken", -1L)
        .containsEntry("mizan_lat_count", 1_000L);
    assertThat(v.get("mizan_lat_us{quantile=\"0.5\"}")).isBetween(499L, 501L);
    assertThat(v.get("mizan_lat_us{quantile=\"0.99\"}")).isBetween(989L, 991L);
    assertThat(v.get("mizan_lat_us_max")).isBetween(999L, 1_001L);
    // Cumulative across scrapes.
    t.recordNanos(5_000_000);
    assertThat(parse(m.render())).containsEntry("mizan_lat_count", 1_001L);
  }

  @Test
  void liveNodeExposesMetricsOverHttp() throws Exception {
    try (Node node = NodeIntegrationTest.start();
        MetricsServer server = MetricsServer.start(0, node.metrics()::render)) {
      var p = LoadGenerator.Params.of("127.0.0.1", node.gatewayPort(), 4, 50, 50, 3);
      LoadGenerator.setup(p);
      LoadGenerator.Result r = LoadGenerator.run(p);
      assertThat(r.errors()).isZero();
      NodeIntegrationTest.awaitHeightStable(node);

      HttpResponse<String> resp =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.headers().firstValue("Content-Type"))
          .hasValueSatisfying(ct -> assertThat(ct).startsWith("text/plain"));
      Map<String, Long> v = parse(resp.body());
      long cmds = r.accepted() + r.rejected();
      assertThat(v.get("mizan_sequencer_execute_count")).isGreaterThanOrEqualTo(cmds);
      assertThat(v.get("mizan_gateway_ingest_to_ack_count")).isGreaterThanOrEqualTo(cmds);
      assertThat(v.get("mizan_gateway_ingest_to_final_count")).isPositive();
      assertThat(v.get("mizan_sequencer_cut_to_commit_count")).isPositive();
      assertThat(v)
          .containsKeys(
              "mizan_gateway_busy_total",
              "mizan_sequencer_rollbacks_total",
              "mizan_sequencer_inbound_depth",
              "mizan_gateway_connections");
      assertThat(v.get("mizan_chain_height")).isEqualTo(node.height());
    }
  }

  /** Parses {@code name value} lines. */
  static Map<String, Long> parse(String text) {
    Map<String, Long> out = new TreeMap<>();
    for (String line : List.of(text.split("\n"))) {
      int sp = line.lastIndexOf(' ');
      if (sp > 0) {
        out.put(line.substring(0, sp), Long.parseLong(line.substring(sp + 1)));
      }
    }
    return out;
  }
}
