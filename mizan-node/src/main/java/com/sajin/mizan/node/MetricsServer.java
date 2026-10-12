package com.sajin.mizan.node;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** Plain-text {@code GET /metrics} on the JDK {@link HttpServer}, bound to loopback (M9-T1). */
public final class MetricsServer implements AutoCloseable {
  private final HttpServer server;

  private MetricsServer(HttpServer server) {
    this.server = server;
  }

  /** Serves {@code body} at {@code http://127.0.0.1:port/metrics} ({@code 0} = ephemeral). */
  public static MetricsServer start(int port, Supplier<String> body) throws IOException {
    HttpServer s =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
    s.createContext(
        "/metrics",
        ex -> {
          try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
              ex.sendResponseHeaders(405, -1);
              return;
            }
            respond(ex, body.get());
          }
        });
    s.start(); // default executor: the server's own dispatcher thread
    return new MetricsServer(s);
  }

  private static void respond(HttpExchange ex, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    ex.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
