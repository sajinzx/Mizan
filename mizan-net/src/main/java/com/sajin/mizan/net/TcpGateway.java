package com.sajin.mizan.net;

import com.sajin.mizan.core.CommandCodec;
import com.sajin.mizan.core.CommandSink;
import com.sajin.mizan.core.EngineListener;
import com.sajin.mizan.core.ExecResult;
import com.sajin.mizan.core.FinalityListener;
import com.sajin.mizan.core.Metrics;
import com.sajin.mizan.core.ProtocolException;
import com.sajin.mizan.core.ResultListener;
import com.sajin.mizan.core.SignedCommand;
import com.sajin.mizan.core.Trade;
import com.sajin.mizan.net.Messages.ServerMessage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-facing TCP gateway (§5.5, M4). One virtual thread reads each connection and a second
 * virtual thread writes it.
 *
 * <p>An {@code ORDER} goes through: decode → cached outcome? → rate limits → signature pre-check →
 * {@link CommandSink#submit}. The answer is {@code BUSY}, or a later {@code ACCEPTED_PROVISIONAL} /
 * {@code REJECTED} delivered through {@link #onResult}, and finally {@code FINAL} via {@link
 * #onFinal}. Outcomes are cached by {@code (account, nonce)} (defect 8), so a retry after a lost
 * ack returns the original answer without re-executing anything.
 *
 * <p>The listener callbacks run on the sequencer and consensus threads and never block: they only
 * enqueue frames. Each connection has a bounded ack queue (overflow ⇒ the client is too slow and is
 * disconnected) and a bounded push queue for {@code TRADE}/{@code REVOKE}/{@code SNAPSHOT} that
 * drops the oldest frame when full.
 *
 * <p>Hardening (M4-T4): maximum connections, idle and per-frame deadlines (slow-loris), per-
 * connection and per-account token buckets (excess ⇒ {@code BUSY}), and disconnection after
 * repeated protocol errors.
 */
public final class TcpGateway
    implements ResultListener, FinalityListener, EngineListener, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(TcpGateway.class);

  /** Cheap signature check before the sequencer; must be thread-safe and side-effect free. */
  @FunctionalInterface
  public interface PreCheck {
    PreCheck ACCEPT_ALL = c -> true;

    boolean ok(SignedCommand c);
  }

  /** Gateway limits. */
  public record Config(
      String bindHost,
      int port,
      int maxConnections,
      long idleTimeoutMs,
      long frameTimeoutMs,
      int pollMs,
      double connectionRate,
      double connectionBurst,
      double accountRate,
      double accountBurst,
      int maxProtocolErrors,
      int ackQueue,
      int pushQueue) {

    /** Sensible localhost defaults. {@code port} 0 picks an ephemeral port. */
    public static Config defaults(int port) {
      return new Config(
          "127.0.0.1",
          port,
          1_000,
          60_000,
          5_000,
          200,
          20_000,
          2_000,
          20_000,
          2_000,
          3,
          4_096,
          512);
    }

    public Config withLimits(double rate, double burst) {
      return new Config(
          bindHost,
          port,
          maxConnections,
          idleTimeoutMs,
          frameTimeoutMs,
          pollMs,
          rate,
          burst,
          rate,
          burst,
          maxProtocolErrors,
          ackQueue,
          pushQueue);
    }

    public Config withTimeouts(long idleMs, long frameMs) {
      return new Config(
          bindHost,
          port,
          maxConnections,
          idleMs,
          frameMs,
          pollMs,
          connectionRate,
          connectionBurst,
          accountRate,
          accountBurst,
          maxProtocolErrors,
          ackQueue,
          pushQueue);
    }

    public Config withMaxConnections(int n) {
      return new Config(
          bindHost,
          port,
          n,
          idleTimeoutMs,
          frameTimeoutMs,
          pollMs,
          connectionRate,
          connectionBurst,
          accountRate,
          accountBurst,
          maxProtocolErrors,
          ackQueue,
          pushQueue);
    }
  }

  private record Key(long account, long nonce) {}

  /** Bound on in-flight latency timestamps (commands lost with their connection never finish). */
  private static final int MAX_TIMED = 100_000;

  private final CommandSink sink;
  private final PreCheck preCheck;
  private final ResultCache cache;
  private final Config config;
  private final Semaphore slots;
  private final Set<Conn> conns = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<Key, Conn> awaiting = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Key, Long> ingestNanos = new ConcurrentHashMap<>();
  private final Metrics.Timer ingestToAck;
  private final Metrics.Timer ingestToFinal;
  private final Metrics.Counter busy;
  private final Metrics.Counter preCheckRejected;
  private final ConcurrentHashMap<Long, Set<Conn>> byAccount = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, TokenBucket> accountBuckets = new ConcurrentHashMap<>();
  private final AtomicLong connectionIds = new AtomicLong();
  private volatile Supplier<byte[]> snapshotProvider;
  private volatile boolean closed;
  private ServerSocket server;
  private Thread acceptor;

  public TcpGateway(CommandSink sink, PreCheck preCheck, ResultCache cache, Config config) {
    this(sink, preCheck, cache, config, Metrics.NOOP);
  }

  public TcpGateway(
      CommandSink sink, PreCheck preCheck, ResultCache cache, Config config, Metrics metrics) {
    this.sink = Objects.requireNonNull(sink, "sink");
    this.preCheck = Objects.requireNonNull(preCheck, "preCheck");
    this.cache = Objects.requireNonNull(cache, "cache");
    this.config = Objects.requireNonNull(config, "config");
    this.slots = new Semaphore(config.maxConnections());
    this.ingestToAck = metrics.timer("gateway_ingest_to_ack");
    this.ingestToFinal = metrics.timer("gateway_ingest_to_final");
    this.busy = metrics.counter("gateway_busy");
    this.preCheckRejected = metrics.counter("gateway_precheck_rejected");
    metrics.gauge("gateway_connections", conns::size);
    metrics.gauge("gateway_awaiting", awaiting::size);
  }

  /** Supplies {@code SNAPSHOT} payloads for {@code SNAPSHOT_REQ} (market data, M5). */
  public void setSnapshotProvider(Supplier<byte[]> provider) {
    this.snapshotProvider = provider;
  }

  public void start() throws IOException {
    server = new ServerSocket();
    server.setReuseAddress(true);
    server.bind(
        new InetSocketAddress(InetAddress.getByName(config.bindHost()), config.port()), 256);
    acceptor = Thread.ofPlatform().name("gateway-accept").daemon(true).start(this::acceptLoop);
  }

  public int port() {
    return server.getLocalPort();
  }

  public int connectionCount() {
    return conns.size();
  }

  public ResultCache cache() {
    return cache;
  }

  private void acceptLoop() {
    while (!closed) {
      try {
        Socket s = server.accept();
        if (!slots.tryAcquire()) {
          rejectConnection(s);
          continue;
        }
        Conn c = new Conn(s, connectionIds.incrementAndGet());
        conns.add(c);
        c.start();
      } catch (IOException e) {
        if (!closed) {
          LOG.atWarn().setMessage("gateway_accept_failed").addKeyValue("error", e.toString()).log();
        }
      }
    }
  }

  private static void rejectConnection(Socket s) {
    try (s) {
      s.getOutputStream().write(new Messages.Err(Messages.ERR_TOO_MANY_CONNECTIONS).encode());
    } catch (IOException ignored) {
      // best effort
    }
  }

  // ------------------------------------------------------------------ engine-side callbacks

  @Override
  public void onResult(SignedCommand c, ExecResult r) {
    Key k = new Key(c.accountId(), c.nonce());
    Conn conn = awaiting.get(k);
    Long t0 = ingestNanos.get(k);
    if (t0 != null) {
      ingestToAck.recordNanos(System.nanoTime() - t0);
    }
    switch (r.status()) {
      case ACCEPTED -> {
        cache.put(k.account(), k.nonce(), provisional(r.orderId()));
        send(conn, new Messages.Accepted(k.account(), k.nonce(), r.orderId()));
      }
      case REJECTED -> {
        cache.put(
            k.account(),
            k.nonce(),
            new ResultCache.Entry(ResultCache.State.REJECTED, 0, r.reasonCode(), r.reason(), 0, 0));
        send(conn, new Messages.Rejected(k.account(), k.nonce(), r.reasonCode(), r.reason()));
      }
      case AUTH_FAILED -> {
        // Not executed and never in a block. If an earlier submission with the same key was
        // executed, answer with that outcome; never cache the auth failure itself.
        awaiting.remove(k, conn);
        ingestNanos.remove(k);
        ResultCache.Entry earlier = cache.get(k.account(), k.nonce());
        send(
            conn,
            earlier != null
                ? answer(k.account(), k.nonce(), earlier)
                : new Messages.Rejected(k.account(), k.nonce(), r.reasonCode(), r.reason()));
      }
    }
  }

  @Override
  public void onFinal(long height, List<SignedCommand> cmds, List<ExecResult> results) {
    for (int i = 0; i < cmds.size(); i++) {
      SignedCommand c = cmds.get(i);
      ExecResult r = results.get(i);
      int status =
          r.status() == ExecResult.Status.ACCEPTED
              ? Messages.Final.ACCEPTED
              : Messages.Final.REJECTED;
      cache.put(
          c.accountId(),
          c.nonce(),
          new ResultCache.Entry(
              ResultCache.State.FINAL, r.orderId(), r.reasonCode(), r.reason(), height, status));
      Key k = new Key(c.accountId(), c.nonce());
      Conn conn = awaiting.remove(k);
      Long t0 = ingestNanos.remove(k);
      if (t0 != null) {
        ingestToFinal.recordNanos(System.nanoTime() - t0);
      }
      send(conn, new Messages.Final(c.accountId(), c.nonce(), height, status, r.orderId()));
    }
  }

  @Override
  public void onTrade(Trade t) {
    Messages.TradeMsg msg = new Messages.TradeMsg(t);
    pushTo(t.buyAccount(), msg);
    if (t.sellAccount() != t.buyAccount()) {
      pushTo(t.sellAccount(), msg);
    }
  }

  @Override
  public void onRevoke(long fromTradeSeq) {
    byte[] frame = new Messages.Revoke(fromTradeSeq).encode();
    for (Conn c : conns) {
      c.push(frame);
    }
  }

  private void pushTo(long account, ServerMessage msg) {
    Set<Conn> set = byAccount.get(account);
    if (set != null) {
      byte[] frame = msg.encode();
      for (Conn c : set) {
        c.push(frame);
      }
    }
  }

  private static ResultCache.Entry provisional(long orderId) {
    return new ResultCache.Entry(ResultCache.State.PROVISIONAL, orderId, 0, null, 0, 0);
  }

  private static ServerMessage answer(long account, long nonce, ResultCache.Entry e) {
    return switch (e.state()) {
      case PROVISIONAL -> new Messages.Accepted(account, nonce, e.orderId());
      case REJECTED -> new Messages.Rejected(account, nonce, e.reasonCode(), e.reason());
      case FINAL -> new Messages.Final(account, nonce, e.height(), e.finalStatus(), e.orderId());
    };
  }

  private static void send(Conn conn, ServerMessage m) {
    if (conn != null) {
      conn.ack(m.encode());
    }
  }

  // ------------------------------------------------------------------ connection

  private final class Conn {
    private final Socket socket;
    private final long id;
    private final TokenBucket bucket;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final ArrayDeque<byte[]> acks = new ArrayDeque<>();
    private final ArrayDeque<byte[]> pushes = new ArrayDeque<>();
    private final Set<Long> accounts = ConcurrentHashMap.newKeySet();
    private volatile boolean dead;
    private int protocolErrors;

    Conn(Socket socket, long id) {
      this.socket = socket;
      this.id = id;
      this.bucket =
          new TokenBucket(config.connectionRate(), config.connectionBurst(), System::nanoTime);
    }

    void start() {
      Thread.ofVirtual().name("gateway-" + id + "-r").start(this::readLoop);
      Thread.ofVirtual().name("gateway-" + id + "-w").start(this::writeLoop);
    }

    void ack(byte[] frame) {
      lock.lock();
      try {
        if (dead) {
          return;
        }
        if (acks.size() >= config.ackQueue()) {
          acks.clear();
          pushes.clear();
          acks.add(new Messages.Err(Messages.ERR_SLOW_CONSUMER).encode());
          dead = true; // writer flushes the ERR, then closes
        } else {
          acks.addLast(frame);
        }
        ready.signal();
      } finally {
        lock.unlock();
      }
    }

    void push(byte[] frame) {
      lock.lock();
      try {
        if (dead) {
          return;
        }
        if (pushes.size() >= config.pushQueue()) {
          pushes.pollFirst(); // drop oldest
        }
        pushes.addLast(frame);
        ready.signal();
      } finally {
        lock.unlock();
      }
    }

    /** Queues a final frame and closes after it is written. */
    void fail(int errCode) {
      lock.lock();
      try {
        if (!dead) {
          acks.addLast(new Messages.Err(errCode).encode());
          dead = true;
          ready.signal();
        }
      } finally {
        lock.unlock();
      }
    }

    private void writeLoop() {
      try (OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024)) {
        while (true) {
          byte[] frame;
          boolean more;
          lock.lock();
          try {
            while (acks.isEmpty() && pushes.isEmpty() && !dead && !closed) {
              ready.await(1, TimeUnit.SECONDS);
            }
            frame = !acks.isEmpty() ? acks.pollFirst() : pushes.pollFirst();
            more = !acks.isEmpty() || !pushes.isEmpty();
          } finally {
            lock.unlock();
          }
          if (frame == null) { // dead or closed, and everything queued has been written
            out.flush();
            break;
          }
          out.write(frame);
          if (!more) {
            out.flush();
          }
        }
      } catch (IOException | InterruptedException e) {
        // connection gone
      } finally {
        shutdown();
      }
    }

    private void readLoop() {
      try {
        socket.setSoTimeout(config.pollMs());
        socket.setTcpNoDelay(true);
        DeadlineInputStream din =
            new DeadlineInputStream(
                socket.getInputStream(),
                TimeUnit.MILLISECONDS.toNanos(config.idleTimeoutMs()),
                TimeUnit.MILLISECONDS.toNanos(config.frameTimeoutMs()),
                System::nanoTime);
        BufferedInputStream in = new BufferedInputStream(din, 16 * 1024);
        while (!dead && !closed) {
          din.awaitFrame();
          FrameCodec.Frame f = FrameCodec.read(in);
          if (f == null) {
            break;
          }
          switch (f.type()) {
            case FrameCodec.ORDER -> handleOrder(f.payload());
            case FrameCodec.QUERY_STATUS -> handleQuery(f.payload());
            case FrameCodec.SNAPSHOT_REQ -> handleSnapshot();
            default -> protocolError(Messages.ERR_UNKNOWN_TYPE);
          }
        }
      } catch (SocketTimeoutException e) {
        fail(Messages.ERR_TIMEOUT);
      } catch (ProtocolException e) {
        fail(Messages.ERR_BAD_FRAME);
      } catch (IOException e) {
        // peer closed or reset
      } finally {
        lock.lock();
        try {
          dead = true;
          ready.signal();
        } finally {
          lock.unlock();
        }
      }
    }

    private void protocolError(int code) {
      protocolErrors++;
      if (protocolErrors >= config.maxProtocolErrors()) {
        fail(code);
      } else {
        ack(new Messages.Err(code).encode());
      }
    }

    private void handleOrder(byte[] envelope) {
      SignedCommand c;
      try {
        c = CommandCodec.decode(envelope);
      } catch (ProtocolException e) {
        protocolError(Messages.ERR_BAD_ENVELOPE);
        return;
      }
      long account = c.accountId();
      if (accounts.add(account)) {
        byAccount.computeIfAbsent(account, a -> ConcurrentHashMap.newKeySet()).add(this);
      }
      ResultCache.Entry cached = cache.get(account, c.nonce());
      if (cached != null) {
        ack(answer(account, c.nonce(), cached).encode());
        return;
      }
      TokenBucket perAccount =
          accountBuckets.computeIfAbsent(
              account,
              a -> new TokenBucket(config.accountRate(), config.accountBurst(), System::nanoTime));
      if (!bucket.tryAcquire() || !perAccount.tryAcquire()) {
        busy.inc();
        ack(new Messages.Busy(account, c.nonce()).encode());
        return;
      }
      if (!preCheck.ok(c)) {
        preCheckRejected.inc();
        ack(
            new Messages.Rejected(
                    account, c.nonce(), Messages.REASON_PRECHECK, "signature pre-check failed")
                .encode());
        return;
      }
      Key k = new Key(account, c.nonce());
      awaiting.put(k, this);
      if (ingestNanos.size() < MAX_TIMED) {
        ingestNanos.put(k, System.nanoTime());
      }
      if (!sink.submit(c)) {
        awaiting.remove(k, this);
        ingestNanos.remove(k);
        busy.inc();
        ack(new Messages.Busy(account, c.nonce()).encode());
      }
    }

    private void handleQuery(byte[] payload) {
      long[] q;
      try {
        q = Messages.parseQuery(payload);
      } catch (ProtocolException e) {
        protocolError(Messages.ERR_BAD_FRAME);
        return;
      }
      ResultCache.Entry e = cache.get(q[0], q[1]);
      Messages.Status s;
      if (e == null) {
        s = new Messages.Status(q[0], q[1], Messages.Status.UNKNOWN, 0, 0);
      } else {
        int state =
            switch (e.state()) {
              case PROVISIONAL -> Messages.Status.PROVISIONAL;
              case REJECTED -> Messages.Status.REJECTED;
              case FINAL -> Messages.Status.FINAL;
            };
        s = new Messages.Status(q[0], q[1], state, e.height(), e.orderId());
      }
      ack(s.encode());
    }

    private void handleSnapshot() {
      Supplier<byte[]> p = snapshotProvider;
      if (p == null) {
        protocolError(Messages.ERR_UNKNOWN_TYPE);
      } else {
        ack(new Messages.Snapshot(p.get()).encode());
      }
    }

    private void shutdown() {
      dead = true;
      try {
        socket.close();
      } catch (IOException ignored) {
        // closing
      }
      if (conns.remove(this)) {
        slots.release();
      }
      for (Long a : accounts) {
        byAccount.computeIfPresent(
            a,
            (k, set) -> {
              set.remove(this);
              return set.isEmpty() ? null : set;
            });
      }
      awaiting.values().removeIf(c -> c == this);
    }
  }

  @Override
  public void close() {
    closed = true;
    try {
      if (server != null) {
        server.close();
      }
    } catch (IOException ignored) {
      // closing
    }
    for (Conn c : conns) {
      c.fail(Messages.ERR_SHUTTING_DOWN);
    }
    if (acceptor != null) {
      try {
        acceptor.join(2_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
