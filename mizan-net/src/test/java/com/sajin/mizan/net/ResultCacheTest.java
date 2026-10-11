package com.sajin.mizan.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** M4-T3 (cache bounds) and the rate limiter used by M4-T4. */
class ResultCacheTest {

  static ResultCache.Entry prov(long id) {
    return new ResultCache.Entry(ResultCache.State.PROVISIONAL, id, 0, null, 0, 0);
  }

  static ResultCache.Entry fin(long id) {
    return new ResultCache.Entry(ResultCache.State.FINAL, id, 0, null, 3, Messages.Final.ACCEPTED);
  }

  @Test
  void perAccountLruBound() {
    ResultCache c = new ResultCache(3, 100);
    for (long n = 1; n <= 3; n++) {
      c.put(7, n, prov(n));
    }
    assertThat(c.get(7, 1)).isNotNull(); // touch 1: now 2 is the least recently used
    c.put(7, 4, prov(4));
    assertThat(c.get(7, 2)).isNull();
    assertThat(c.get(7, 1)).isNotNull();
    assertThat(c.get(7, 3)).isNotNull();
    assertThat(c.get(7, 4)).isNotNull();
    assertThat(c.size()).isEqualTo(3);
  }

  @Test
  void totalLruBoundAcrossAccounts() {
    ResultCache c = new ResultCache(10, 4);
    c.put(1, 1, prov(1));
    c.put(2, 1, prov(2));
    c.put(3, 1, prov(3));
    c.put(4, 1, prov(4));
    c.get(1, 1);
    c.put(5, 1, prov(5)); // evicts account 2's entry (oldest untouched)
    assertThat(c.size()).isEqualTo(4);
    assertThat(c.get(2, 1)).isNull();
    assertThat(c.get(1, 1)).isNotNull();
    for (long a = 10; a < 1_000; a++) {
      c.put(a, 1, prov(a));
    }
    assertThat(c.size()).isEqualTo(4);
  }

  @Test
  void finalIsNeverDowngradedButCanBeUpdated() {
    ResultCache c = new ResultCache(10, 10);
    c.put(1, 1, prov(5));
    c.put(1, 1, fin(5));
    c.put(1, 1, prov(5));
    assertThat(c.get(1, 1).state()).isEqualTo(ResultCache.State.FINAL);
    assertThat(c.size()).isEqualTo(1);
    assertThat(ResultCache.withDefaults().size()).isZero();
    assertThatThrownBy(() -> new ResultCache(0, 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tokenBucketRefillsAtRate() {
    AtomicLong now = new AtomicLong();
    TokenBucket b = new TokenBucket(10, 3, now::get); // 10/s, burst 3
    assertThat(b.tryAcquire()).isTrue();
    assertThat(b.tryAcquire()).isTrue();
    assertThat(b.tryAcquire()).isTrue();
    assertThat(b.tryAcquire()).isFalse();
    now.addAndGet(100_000_000L); // 100 ms => one token
    assertThat(b.tryAcquire()).isTrue();
    assertThat(b.tryAcquire()).isFalse();
    now.addAndGet(10_000_000_000L); // long pause: capped at burst
    for (int i = 0; i < 3; i++) {
      assertThat(b.tryAcquire()).isTrue();
    }
    assertThat(b.tryAcquire()).isFalse();
    assertThatThrownBy(() -> new TokenBucket(0, 1, now::get))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
