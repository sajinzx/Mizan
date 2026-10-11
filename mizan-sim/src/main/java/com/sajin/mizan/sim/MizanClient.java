package com.sajin.mizan.sim;

import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.net.FrameCodec;
import com.sajin.mizan.net.Messages;
import com.sajin.mizan.net.Messages.ServerMessage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Blocking-style client for the gateway protocol (§5.5). A virtual reader thread dispatches server
 * messages to futures keyed by {@code (account, nonce)}: the first answer to an {@code ORDER}
 * ({@code BUSY}, {@code ACCEPTED_PROVISIONAL} or {@code REJECTED}), its later {@code FINAL}, and
 * {@code STATUS} replies. {@code TRADE}, {@code REVOKE} and {@code SNAPSHOT} go to the push
 * listener. Thread-safe.
 */
public final class MizanClient implements AutoCloseable {
  private record Key(long account, long nonce) {}

  private final Socket socket;
  private final OutputStream out;
  private final ReentrantLock writeLock = new ReentrantLock();
  private final ConcurrentHashMap<Key, CompletableFuture<ServerMessage>> acks =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Key, CompletableFuture<Messages.Final>> finals =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Key, CompletableFuture<Messages.Status>> statuses =
      new ConcurrentHashMap<>();
  private volatile CompletableFuture<Messages.Snapshot> pendingSnapshot = new CompletableFuture<>();
  private final Consumer<ServerMessage> pushes;
  private final Thread reader;
  private volatile IOException failure;
  private final AtomicLong busyRetries = new AtomicLong();

  public MizanClient(String host, int port, Consumer<ServerMessage> pushes) throws IOException {
    this.socket = new Socket(host, port);
    socket.setTcpNoDelay(true);
    this.out = new BufferedOutputStream(socket.getOutputStream());
    this.pushes = pushes == null ? m -> {} : pushes;
    InputStream in = new BufferedInputStream(socket.getInputStream());
    this.reader = Thread.ofVirtual().name("client-reader").start(() -> readLoop(in));
  }

  public MizanClient(String host, int port) throws IOException {
    this(host, port, null);
  }

  private void readLoop(InputStream in) {
    try {
      FrameCodec.Frame f;
      while ((f = FrameCodec.read(in)) != null) {
        dispatch(Messages.decode(f));
      }
      fail(new IOException("server closed the connection"));
    } catch (IOException e) {
      fail(e);
    }
  }

  private void dispatch(ServerMessage m) {
    switch (m) {
      case Messages.Busy b -> complete(acks, new Key(b.account(), b.nonce()), m);
      case Messages.Accepted a -> complete(acks, new Key(a.account(), a.nonce()), m);
      case Messages.Rejected r -> complete(acks, new Key(r.account(), r.nonce()), m);
      case Messages.Final f -> {
        Key k = new Key(f.account(), f.nonce());
        complete(acks, k, m); // a cached FINAL can be the first answer to a retry
        CompletableFuture<Messages.Final> fut = finals.remove(k);
        if (fut != null) {
          fut.complete(f);
        }
      }
      case Messages.Status s -> {
        CompletableFuture<Messages.Status> fut = statuses.remove(new Key(s.account(), s.nonce()));
        if (fut != null) {
          fut.complete(s);
        }
      }
      case Messages.Snapshot s -> {
        pendingSnapshot.complete(s);
        pushes.accept(s);
      }
      case Messages.Err e -> {
        pushes.accept(e);
        fail(new IOException("server error " + e.code()));
      }
      case Messages.TradeMsg t -> pushes.accept(t);
      case Messages.Revoke r -> pushes.accept(r);
    }
  }

  private static <T> void complete(
      ConcurrentHashMap<Key, CompletableFuture<T>> map, Key k, ServerMessage m) {
    CompletableFuture<T> fut = map.remove(k);
    if (fut != null) {
      @SuppressWarnings("unchecked")
      T t = (T) m;
      fut.complete(t);
    }
  }

  private void fail(IOException e) {
    failure = e;
    acks.values().forEach(f -> f.completeExceptionally(e));
    finals.values().forEach(f -> f.completeExceptionally(e));
    statuses.values().forEach(f -> f.completeExceptionally(e));
    pendingSnapshot.completeExceptionally(e);
  }

  private void write(byte[] frame) throws IOException {
    if (failure != null) {
      throw failure;
    }
    writeLock.lock();
    try {
      out.write(frame);
      out.flush();
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Sends a command. The future completes with the first answer: {@code Busy}, {@code Accepted},
   * {@code Rejected} or (for a retry of an already final command) {@code Final}.
   */
  public CompletableFuture<ServerMessage> send(SignedCommand c) throws IOException {
    Key k = new Key(c.accountId(), c.nonce());
    CompletableFuture<ServerMessage> fut = new CompletableFuture<>();
    acks.put(k, fut);
    finals.computeIfAbsent(k, x -> new CompletableFuture<>());
    write(Messages.order(c.raw()));
    return fut;
  }

  /** Completes when the command's block is final. Call after {@link #send}. */
  public CompletableFuture<Messages.Final> finality(long account, long nonce) {
    return finals.computeIfAbsent(new Key(account, nonce), x -> new CompletableFuture<>());
  }

  public CompletableFuture<Messages.Status> queryStatus(long account, long nonce)
      throws IOException {
    CompletableFuture<Messages.Status> fut = new CompletableFuture<>();
    statuses.put(new Key(account, nonce), fut);
    write(Messages.queryStatus(account, nonce));
    return fut;
  }

  /** Requests a market-data snapshot (M5). */
  public CompletableFuture<Messages.Snapshot> requestSnapshot() throws IOException {
    CompletableFuture<Messages.Snapshot> fut = new CompletableFuture<>();
    pendingSnapshot = fut;
    write(FrameCodec.encode(FrameCodec.SNAPSHOT_REQ, new byte[0]));
    return fut;
  }

  /**
   * Sends {@code c} and retries with the <em>same</em> command while the answer is {@code BUSY},
   * backing off from 50 µs up to 20 ms. Returns the first non-BUSY answer.
   */
  public ServerMessage submit(SignedCommand c, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    long deadline = System.nanoTime() + timeout.toNanos();
    long backoffNanos = 50_000;
    while (true) {
      ServerMessage m = await(send(c), deadline);
      if (!(m instanceof Messages.Busy)) {
        return m;
      }
      busyRetries.incrementAndGet();
      if (System.nanoTime() > deadline) {
        throw new TimeoutException("still BUSY at deadline");
      }
      TimeUnit.NANOSECONDS.sleep(backoffNanos);
      backoffNanos = Math.min(backoffNanos * 2, 20_000_000);
    }
  }

  public long busyRetries() {
    return busyRetries.get();
  }

  public static <T> T await(CompletableFuture<T> f, long deadlineNanos)
      throws IOException, InterruptedException, TimeoutException {
    try {
      return f.get(Math.max(1, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (ExecutionException e) {
      throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
    }
  }

  @Override
  public void close() throws IOException {
    socket.close();
    try {
      reader.join(2_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
