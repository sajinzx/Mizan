package com.sajin.mizan.net;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.function.LongSupplier;

/**
 * Wraps a socket stream whose {@code SO_TIMEOUT} is a short poll interval and enforces two
 * deadlines on top of it:
 *
 * <ul>
 *   <li><b>idle</b>: no new frame starts within {@code idleNanos};
 *   <li><b>frame</b>: a frame that has started must complete within {@code frameNanos}. This
 *       defeats slow-loris clients that trickle one byte at a time.
 * </ul>
 *
 * <p>Call {@link #awaitFrame()} before reading each frame. Deadline expiry throws {@link
 * SocketTimeoutException}.
 */
final class DeadlineInputStream extends FilterInputStream {
  private final long idleNanos;
  private final long frameNanos;
  private final LongSupplier clock;
  private long idleSince;
  private long frameStart = -1;

  DeadlineInputStream(InputStream in, long idleNanos, long frameNanos, LongSupplier clock) {
    super(in);
    this.idleNanos = idleNanos;
    this.frameNanos = frameNanos;
    this.clock = clock;
    this.idleSince = clock.getAsLong();
  }

  /** Marks the boundary between frames. */
  void awaitFrame() {
    frameStart = -1;
    idleSince = clock.getAsLong();
  }

  @Override
  public int read() throws IOException {
    while (true) {
      try {
        int b = super.read();
        started();
        return b;
      } catch (SocketTimeoutException e) {
        checkDeadlines();
      }
    }
  }

  @Override
  public int read(byte[] buf, int off, int len) throws IOException {
    while (true) {
      try {
        int n = super.read(buf, off, len);
        started();
        return n;
      } catch (SocketTimeoutException e) {
        checkDeadlines();
      }
    }
  }

  private void started() {
    if (frameStart < 0) {
      frameStart = clock.getAsLong();
    }
  }

  private void checkDeadlines() throws SocketTimeoutException {
    long now = clock.getAsLong();
    if (frameStart >= 0) {
      if (now - frameStart > frameNanos) {
        throw new SocketTimeoutException("frame not completed in time");
      }
    } else if (now - idleSince > idleNanos) {
      throw new SocketTimeoutException("idle timeout");
    }
  }
}
