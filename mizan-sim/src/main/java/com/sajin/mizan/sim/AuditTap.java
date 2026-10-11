package com.sajin.mizan.sim;

import com.sajin.mizan.core.Trade;
import com.sajin.mizan.core.TradeListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

/**
 * Legacy-style audit trail of trades over piped streams (§6.5, defect 5), kept as a demo of
 * inter-thread communication. It is off the hot path:
 *
 * <pre>
 * engine thread --onTrade--> bounded SPSC queue (drop + count when full)
 *     --> "audit-logger" thread --> PipedOutputStream
 *     --> PipedInputStream --> "audit-reader" thread --> sink
 * </pre>
 *
 * <p>A pipe write blocks when the reader is slow, so it happens only on the {@code audit-logger}
 * thread, never on the caller of {@link #onTrade}. When the queue is full the trade is dropped and
 * {@link #droppedCount()} increments; the engine is never slowed down.
 */
public final class AuditTap implements TradeListener, AutoCloseable {
  private static final int PIPE_BYTES = 4096;

  private final OneToOneConcurrentArrayQueue<Trade> queue;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong written = new AtomicLong();
  private final PipedOutputStream out = new PipedOutputStream();
  private final PipedInputStream in;
  private final Thread logger;
  private final Thread reader;
  private volatile boolean running = true;

  /**
   * @param capacity queue capacity (rounded up to a power of two)
   * @param sink receives one line per trade, on the {@code audit-reader} thread
   */
  public AuditTap(int capacity, Consumer<String> sink) throws IOException {
    Objects.requireNonNull(sink, "sink");
    this.queue = new OneToOneConcurrentArrayQueue<>(capacity);
    this.in = new PipedInputStream(out, PIPE_BYTES);
    this.logger = Thread.ofPlatform().name("audit-logger").daemon(true).unstarted(this::logLoop);
    this.reader =
        Thread.ofPlatform().name("audit-reader").daemon(true).unstarted(() -> readLoop(sink));
    logger.start();
    reader.start();
  }

  /** Called on the engine thread. Never blocks. */
  @Override
  public void onTrade(Trade t) {
    if (!queue.offer(t)) {
      dropped.incrementAndGet();
    }
  }

  /** Trades dropped because the queue was full. */
  public long droppedCount() {
    return dropped.get();
  }

  /** Trades written into the pipe. */
  public long writtenCount() {
    return written.get();
  }

  static String format(Trade t) {
    return "TRADE seq="
        + t.seq()
        + " instr="
        + t.instrument()
        + " maker="
        + t.makerOrderId()
        + " taker="
        + t.takerOrderId()
        + " buy="
        + t.buyAccount()
        + " sell="
        + t.sellAccount()
        + " side="
        + t.takerSide()
        + " px="
        + t.price()
        + " qty="
        + t.qty()
        + "\n";
  }

  private void logLoop() {
    try {
      while (running || !queue.isEmpty()) {
        Trade t = queue.poll();
        if (t == null) {
          Thread.sleep(1);
          continue;
        }
        out.write(format(t).getBytes(StandardCharsets.US_ASCII)); // may block: fine here
        written.incrementAndGet();
      }
    } catch (IOException | InterruptedException e) {
      // pipe closed or shutting down
    } finally {
      try {
        out.close();
      } catch (IOException ignored) {
        // already closed
      }
    }
  }

  private void readLoop(Consumer<String> sink) {
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
      String line;
      while ((line = r.readLine()) != null) {
        sink.accept(line);
      }
    } catch (IOException e) {
      // writer gone or pipe closed
    }
  }

  /** Flushes queued trades (if the reader keeps up), then stops both threads. */
  @Override
  public void close() {
    running = false;
    try {
      logger.join(2_000);
      if (logger.isAlive()) {
        logger.interrupt();
        closeQuietly(in); // unblocks a writer stuck on a full pipe
        logger.join(2_000);
      }
      reader.join(2_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void closeQuietly(PipedInputStream s) {
    try {
      s.close();
    } catch (IOException ignored) {
      // already closed
    }
  }
}
