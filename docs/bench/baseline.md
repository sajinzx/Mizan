# Baseline (M3-T5), 2026-10-12

This is the first measurement, taken before any optimization. Every number below comes from [`raw/2026-10-12-baseline/results.json`](raw/2026-10-12-baseline/results.json) (human-readable log: [`human.txt`](raw/2026-10-12-baseline/human.txt)). The numbers describe this one laptop. They are not a claim about any other machine.

## Environment

| | |
|---|---|
| CPU | AMD Ryzen 7 4700U (8 cores / 8 threads, 2.0 GHz base), laptop on AC power |
| RAM | 7.4 GiB |
| OS | Windows 11 Home 10.0.26200 |
| JDK (benchmark JVM) | OpenJDK 64-Bit Server VM 21.0.12.1+1-LTS (Gradle foojay toolchain) |
| GC / heap | JVM defaults (G1, ergonomic heap); no tuning flags |
| JVM flags | only JMH/Gradle defaults (`-Dfile.encoding=UTF-8`, temp dir, locale) |
| JMH | 1.37, 2 forks × (3 × 2 s warmup + 5 × 2 s measurement) per benchmark |
| Build | commit `831e881` (M3-T4) |

The machine was otherwise idle apart from the desktop session. Nothing was pinned to a core and frequency scaling was left on. SampleTime timestamps on this Windows JVM have about 100 ns granularity, which is why sub-microsecond percentiles show as 0.1/0.2 µs steps.

## Workloads

- **`ExecuteBench`**: single-threaded `ExecutionCore.execute` over a deterministic stream of 32,768 pre-signed commands. Ten funded traders on one instrument around price 100: 60% GTC limits, 15% IOC limits, 10% market orders, 15% cancels of their own resting orders. Every command is accepted, and the stream includes matching, settlement and fees. When the stream is used up, the core is reset from a copy of the initial state, so the rare reset is included in the samples (it is visible in `max`). The state root is **not** computed per command; it is computed per block and measured separately later (§10).
  - `signatures=off`: verification disabled (benchmark-only engine flag).
  - `signatures=ed25519`: each command verified with the JDK `Ed25519` provider, no cache.
- **`BookBench`**: `OrderBook` alone. `depth` price levels per side, 4 orders per level. `addCancel` rests one bid at a level spread across the book and cancels it. `matchOne` sends a 1-lot buy against the best ask; makers are huge, so the book stays in a steady state.

## Results

Throughput (ops/s, mean ± 99.9% CI):

| Benchmark | Param | Throughput |
|---|---|---|
| ExecuteBench | signatures=off | **3.40 M/s** ± 0.13 M |
| ExecuteBench | signatures=ed25519 | **1,027/s** ± 110 |
| BookBench.addCancel | depth=10 | 17.2 M/s ± 1.8 M |
| BookBench.addCancel | depth=100 | 14.2 M/s ± 1.2 M |
| BookBench.addCancel | depth=1000 | 7.9 M/s ± 0.4 M |
| BookBench.matchOne | depth=10 | 39.6 M/s ± 2.6 M |
| BookBench.matchOne | depth=100 | 36.0 M/s ± 2.5 M |
| BookBench.matchOne | depth=1000 | 32.2 M/s ± 3.7 M |

Latency per operation (SampleTime, µs). `max` is the largest **observed** sample, not a bound:

| Benchmark | Param | Samples | p50 | p99 | p99.9 | max (observed) |
|---|---|---|---|---|---|---|
| ExecuteBench | signatures=off | 450,181 | 0.4 | 1.1 | 26.3 | 2,765 |
| ExecuteBench | signatures=ed25519 | 23,401 | 798.7 | 1,650.6 | 2,883.4 | 16,712 |
| BookBench.addCancel | depth=10 | 573,285 | 0.1 | 0.2 | 9.1 | 1,198 |
| BookBench.addCancel | depth=100 | 501,260 | 0.1 | 0.4 | 5.4 | 1,155 |
| BookBench.addCancel | depth=1000 | 509,706 | 0.2 | 0.5 | 9.8 | 1,135 |
| BookBench.matchOne | depth=10 | 611,201 | 0.2 | 0.6 | 13.0 | 1,055 |
| BookBench.matchOne | depth=100 | 603,136 | 0.2 | 0.5 | 10.1 | 1,075 |
| BookBench.matchOne | depth=1000 | 541,438 | 0.2 | 0.6 | 13.8 | 2,044 |

## Observations (no optimization done yet)

- **Signature verification dominates.** With verification on, execution is about 3,300× slower than with it off (≈ 0.8 ms per command). `Ed25519Verifier` decodes the X.509 key and creates a `Signature` object on every call, and the JDK implementation is pure Java. The architecture already takes this off the sequencer: the gateway pre-checks on virtual threads, and the engine's `CachingVerifier` lookup is then a hit (ADR 0011). Measuring that path, with and without the cache, is M10-T4.
- **`addCancel` slows down with depth** (17.2 → 7.9 M/s from 10 to 1,000 levels). That fits `TreeMap` lookups plus the O(level size) `ArrayDeque` removal on cancel. M10-T1 (intrusive list + O(1) cancel) targets this.
- **Every benchmark has p99.9 and max far above p50.** These are tens of microseconds to milliseconds against sub-microsecond medians. The likely causes are GC pauses, OS scheduling and timer resolution on a laptop; that will only be confirmed by the M10-T3 GC comparison. Treat the tails as measurements, not explanations.

## Reproduce

```sh
./gradlew :mizan-bench:jmh                         # full run (≈ 10 min); results in mizan-bench/build/results/jmh/
./gradlew :mizan-bench:jmh -Pjmh.quick             # 1-fork smoke run
./gradlew :mizan-bench:jmh -Pjmh.includes=BookBench
```
