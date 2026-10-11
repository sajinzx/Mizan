package com.sajin.mizan.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CachingVerifierTest {

  /** Counts delegate calls so tests can observe cache hits. */
  static final class CountingVerifier implements SignatureVerifier {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
      calls.incrementAndGet();
      return SignatureVerifier.ed25519().verify(publicKey, message, signature);
    }
  }

  @Test
  void positiveResultIsCached() throws CryptoException {
    var counting = new CountingVerifier();
    var cache = new CachingVerifier(counting);
    Signer s = Signer.generate();
    byte[] msg = {1, 2, 3};
    byte[] sig = s.sign(msg);

    assertThat(cache.verify(s.publicKey(), msg, sig)).isTrue();
    assertThat(cache.verify(s.publicKey(), msg, sig)).isTrue();
    assertThat(counting.calls).hasValue(1);
    assertThat(cache.hits()).isEqualTo(1);
    assertThat(cache.misses()).isEqualTo(1);
  }

  @Test
  void negativeResultIsNeverCached() throws CryptoException {
    var counting = new CountingVerifier();
    var cache = new CachingVerifier(counting);
    Signer s = Signer.generate();
    byte[] msg = {1, 2, 3};
    byte[] bad = s.sign(msg);
    bad[5] ^= 1;

    assertThat(cache.verify(s.publicKey(), msg, bad)).isFalse();
    assertThat(cache.verify(s.publicKey(), msg, bad)).isFalse();
    assertThat(counting.calls).hasValue(2);
    assertThat(cache.size()).isZero();
  }

  @Test
  void cachedEntryDoesNotValidateADifferentTriple() throws CryptoException {
    var cache = new CachingVerifier(SignatureVerifier.ed25519());
    Signer a = Signer.generate();
    Signer b = Signer.generate();
    byte[] msg = {9};
    byte[] sig = a.sign(msg);
    assertThat(cache.verify(a.publicKey(), msg, sig)).isTrue();
    assertThat(cache.verify(b.publicKey(), msg, sig)).isFalse();
    assertThat(cache.verify(a.publicKey(), new byte[] {8}, sig)).isFalse();
  }

  @Test
  void fifoEvictionAtCapacity() throws CryptoException {
    var counting = new CountingVerifier();
    var cache = new CachingVerifier(counting, 2);
    Signer s = Signer.generate();
    byte[][] msgs = {{1}, {2}, {3}};
    byte[][] sigs = new byte[3][];
    for (int i = 0; i < 3; i++) {
      sigs[i] = s.sign(msgs[i]);
      assertThat(cache.verify(s.publicKey(), msgs[i], sigs[i])).isTrue();
    }
    assertThat(cache.size()).isEqualTo(2);
    int before = counting.calls.get();
    cache.verify(s.publicKey(), msgs[2], sigs[2]); // newest: hit
    cache.verify(s.publicKey(), msgs[1], sigs[1]); // still cached: hit
    assertThat(counting.calls.get()).isEqualTo(before);
    cache.verify(s.publicKey(), msgs[0], sigs[0]); // oldest was evicted: miss
    assertThat(counting.calls.get()).isEqualTo(before + 1);
  }

  @Test
  void threadSafeUnderConcurrentUse() throws Exception {
    var cache = new CachingVerifier(SignatureVerifier.ed25519(), 64);
    Signer s = Signer.generate();
    int n = 200;
    byte[][] msgs = new byte[n][];
    byte[][] sigs = new byte[n][];
    for (int i = 0; i < n; i++) {
      msgs[i] = new byte[] {(byte) i, (byte) (i >> 8)};
      sigs[i] = s.sign(msgs[i]);
    }
    byte[] pk = s.publicKey();
    List<Future<Integer>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      for (int t = 0; t < 8; t++) {
        int offset = t;
        results.add(
            pool.submit(
                () -> {
                  int ok = 0;
                  for (int k = 0; k < 2_000; k++) {
                    int i = (k * 7 + offset) % n;
                    boolean valid = (k & 3) != 0;
                    byte[] sig = valid ? sigs[i] : sigs[(i + 1) % n];
                    if (cache.verify(pk, msgs[i], sig) == valid) {
                      ok++;
                    }
                  }
                  return ok;
                }));
      }
      for (Future<Integer> f : results) {
        assertThat(f.get()).isEqualTo(2_000);
      }
    }
    assertThat(cache.size()).isLessThanOrEqualTo(64);
  }
}
