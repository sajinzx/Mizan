package com.sajin.mizan.crypto;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Remembers <em>positive</em> verification results so a command verified at the gateway is not
 * re-verified by the engine or by validators (§6.8, defect 7). Keyed by {@code SHA256(pubKey ||
 * message || signature)}, bounded, FIFO eviction, thread-safe.
 *
 * <p>Performance only, never consensus state: a miss falls through to the delegate and gives the
 * same answer. Only fixed-width keys (44 bytes) and signatures (64 bytes) are cached, so the
 * concatenation used as the key is unambiguous.
 */
public final class CachingVerifier implements SignatureVerifier {
  public static final int DEFAULT_CAPACITY = 1 << 18;

  private final SignatureVerifier delegate;
  private final int capacity;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashSet<ByteBuffer> verified = new LinkedHashSet<>();
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();

  public CachingVerifier(SignatureVerifier delegate) {
    this(delegate, DEFAULT_CAPACITY);
  }

  public CachingVerifier(SignatureVerifier delegate, int capacity) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
  }

  @Override
  public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
    if (publicKey == null
        || message == null
        || signature == null
        || publicKey.length != Ed25519Keys.PUBLIC_KEY_BYTES
        || signature.length != SIGNATURE_BYTES) {
      return delegate.verify(publicKey, message, signature);
    }
    ByteBuffer key = ByteBuffer.wrap(Hashing.sha256(publicKey, message, signature));
    lock.lock();
    try {
      if (verified.contains(key)) {
        hits.incrementAndGet();
        return true;
      }
    } finally {
      lock.unlock();
    }
    misses.incrementAndGet();
    boolean ok = delegate.verify(publicKey, message, signature);
    if (ok) {
      lock.lock();
      try {
        if (verified.add(key) && verified.size() > capacity) {
          var oldest = verified.iterator();
          oldest.next();
          oldest.remove();
        }
      } finally {
        lock.unlock();
      }
    }
    return ok;
  }

  public long hits() {
    return hits.get();
  }

  public long misses() {
    return misses.get();
  }

  public int size() {
    lock.lock();
    try {
      return verified.size();
    } finally {
      lock.unlock();
    }
  }
}
