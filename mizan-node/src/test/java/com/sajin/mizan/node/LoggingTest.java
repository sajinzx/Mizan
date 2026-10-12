package com.sajin.mizan.node;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;
import com.sajin.mizan.sim.DevGenesis;
import com.sajin.mizan.testkit.Fixtures;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/** M9-T2: lifecycle events are named messages with key-value details; JSON output is wired. */
class LoggingTest {
  @TempDir Path dir;

  @Test
  void lifecycleEventsCarryKeyValues() throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> list = new ListAppender<>();
    list.start();
    root.addAppender(list);
    try {
      Node.Persistence p = Node.Persistence.at(dir);
      try (Node n =
          Node.startPersistent(
              Node.Settings.dev(DevGenesis.config(), 0),
              p,
              Fixtures.validatorSigner(0),
              List.of(),
              List.of())) {
        assertThat(n.height()).isZero();
      }
    } finally {
      root.detachAppender(list);
    }
    Map<String, Map<String, Object>> events = new TreeMap<>();
    for (ILoggingEvent e : list.list) {
      Map<String, Object> kv = new TreeMap<>();
      List<KeyValuePair> pairs = e.getKeyValuePairs();
      if (pairs != null) {
        pairs.forEach(pair -> kv.put(pair.key, pair.value));
      }
      events.putIfAbsent(e.getMessage(), kv);
    }
    assertThat(events).containsKeys("recovered", "node_start", "node_stop");
    assertThat(events.get("node_start"))
        .containsEntry("mode", "single-validator")
        .containsEntry("height", 0L)
        .containsKey("gatewayPort");
    assertThat(events.get("recovered")).containsKeys("height", "snapshot", "tornBytes", "ms");
  }

  @Test
  void jsonFormatSelectsTheJsonAppender() throws Exception {
    LoggerContext ctx = new LoggerContext();
    ctx.putProperty("mizan.log.format", "JSON");
    JoranConfigurator jc = new JoranConfigurator();
    jc.setContext(ctx);
    jc.doConfigure(LoggingTest.class.getResource("/logback.xml"));
    Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    assertThat(root.getAppender("JSON")).isNotNull();
    assertThat(root.getAppender("TEXT")).isNull();
    assertThat(ctx.getStatusManager().getCopyOfStatusList())
        .noneMatch(s -> s.getEffectiveLevel() >= Status.ERROR); // "TEXT not referenced" is a WARN
    ctx.stop();
  }
}
