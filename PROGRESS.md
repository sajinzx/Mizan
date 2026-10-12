# Progress
Current milestone: M8   Last gate passed: M7 (M1: planted-red CI check outstanding; M5: UI needs a human look; M7: protocol review by a second person outstanding)
Decisions/ADRs: see docs/adr/ (0001–0003 design; 0004 naming, 0005 toolchain versions, 0006 test-scope testkit edges, 0007 core type placement, 0008 engine semantics, 0009 test signer + arch scans)

## M1
- [x] M1-T1 Scaffold — Gradle 9.8.0 wrapper (sha256-pinned), 11 modules, Java 21 toolchain via foojay, Spotless (gjf 1.36.1), Error Prone 2.50.0, `-Xlint:all -Werror` everywhere (stricter than "in CI"), JaCoCo, tasks `nightly`/`recoveryTest`/`simSweep`, `checkModuleGraph` hooked into `check`. Verified: planted `core -> engine` edge fails `checkModuleGraph` ("forbidden edge core -> engine (implementation)"). Deferred: JavaFX plugin is not applied to `mizan-ui` until M5-T4 (catalog entry exists). Known: one Gradle-11 deprecation (`Configuration.setVisible`) comes from a third-party plugin, not our scripts.
- [~] M1-T2 CI — `ci.yml` (build on push/PR) and `nightly.yml` (cron 03:17 UTC + manual; matrix: nightly, simSweep -Pseeds=1000, recoveryTest -Piter=200) written; the three long tasks run green locally. Green CI on `main` confirmed (run on 1262afb). **Still pending:** plant a failing test on a throwaway branch, confirm CI goes red, remove it, and confirm `main` is green.
- [x] M1-T3 Core types — all of §5.4, `Limits`, §5.2 exceptions, `Instrument`, `GenesisConfig` (+ `Accounts`). `CryptoException` moved to crypto (ADR 0007). Confirmed Error Prone is active: a planted `==` on Strings fails the build.
- [x] M1-T4 CommandCodec — §5.3 encode/decode; decode throws only `ProtocolException` (explicit checks, no catch-all). Fuzz: 200k random arrays + 100k bit-flip mutations of valid envelopes, each one either decodes and re-encodes byte-identically or throws `ProtocolException`; truncation/extension rejected at every length. Error Prone `EnumOrdinal` led to explicit frozen `code()` on `Side`/`OrderType`/`TimeInForce` (same values as the §5.3 ordinals). Added testkit `Seed`/`SeedExtension`: `-Pseed=N` replays, and failures print `SEED=N replay: ./gradlew <task> --tests ... -Pseed=N` (checked with a planted failure).
- [x] M1-T5 Crypto — `Hashing`, `SimpleMerkle` (+ `rootOfList` for txRoot, index as key), `Signer` (incl. deterministic `fromSeed`: the JDK derives the key from a 32-byte RFC 8032 seed, no hand-written crypto), `SignatureVerifier` (+ `Ed25519Verifier`, never throws), `CachingVerifier` (positive-only, FIFO, 2^18, caches only 44-byte keys and 64-byte sigs so keys are unambiguous), `CryptoException`. KATs: Merkle roots computed independently with Python hashlib; RFC 8032 vectors 1 and 2 pass.
- [x] M1-T6 Test kit seeds — `Fixtures` (keys derived as `Signer.fromSeed(sha256("mizan-fixture-<kind>" || id))`, genesis fixture with BASE/QUOTE and ALT/QUOTE instruments and n validators), `SignedCommands` (sign/register/deposit/limit/market/cancel + `withBadSignature`, `signedByWrongKey`). Stored vector: admin public key, computed independently with Python `cryptography`.
- [x] M1-T7 Docs — `docs/assumptions.md` (spec §1, fault/storage/clock/crypto assumptions, ack contract), ADRs 0001–0003, README stub linking every doc and ADR (all links checked).

**Gate M1 status (2026-10-12):**
- [x] A fresh clone builds green with `./gradlew build` (44 tests; `checkModuleGraph`: 36 edges, all allowed).
- [x] `checkModuleGraph` is enforced (hooked into `check`; a planted bad edge fails it).
- [x] Codec fuzz ≥ 100k cases yields only `ProtocolException` (200k random + 100k mutated per run).
- [x] CI is green on `main` (GitHub Actions).
- [ ] CI turns red on a planted failure (not done yet; see M1-T2).

## M2
- [x] M2-T1 Orders: sealed `Order` → `LimitOrder`/`MarketOrder`; `Order.of` enforces MARKET⇒IOC.
- [x] M2-T2 PriceLevel: `ArrayDeque` + running total behind the spec API; property-checked against a list model.
- [x] M2-T3 OrderBook: §6.2 match with STP, `FillGuard`, `availableQty` skips own orders (defect 3), `wouldCross`, `depth`, `copy`, `orders()` sorted by id. The index is declared as `HashMap` so ArchUnit can prove it is never iterated.
- [x] M2-T4 Ledger: §6.1 reserve/settle/release/fees/nonces; per-asset supply cap of 2^62 makes settlement overflow impossible (ADR 0008); zero entries are removed.
- [x] M2-T5 ExecutionCore: three-phase `execute`, all TIFs, validation; order id consumed only on acceptance (ADR 0008). `FokTest.marketFokRejected` (defect 4).
- [x] M2-T6 Genesis bootstrap: admin registered, fee account implicit (no key), empty books; `stateRootVersion` 2 refused until M10.
- [x] M2-T7 State root v1: `SimpleStateRoot`; genesis root known-answer value computed independently in Python.
- [x] M2-T8 StateCodec: canonical encode/decode; the decoded core continues identically; every truncation is rejected; 20k mutation fuzz (only `ProtocolException`, and anything that decodes is canonical).
- [x] M2-T9 ReferenceEngine: independent naive engine + `StateDump` + `Differential` (results, events, full state after every command). Mutation check: 4 injected bugs (no taker fee, LIFO, FOK counting own orders, no refund) are all detected.
- [x] M2-T10 Properties: conservation, replay, copy independence, FIFO/book invariants, rejection purity, escrow == resting orders (covers refund, release, market-buy guard, non-negative balances), STP + fee conservation, replay protection, differential equality. Streams of 15–120 ops; 82% of runs have 5 or more trades. Fast test signer (ADR 0009).
- [x] M2-T11 Arch rules: ArchUnit (clock, randomness, threads, I/O/logging, hash iteration, floating point) + source scan for raw `*` (defect 9) and floating point.

Regression checks: each planted bug made its test fail: defect 3, defect 4 (all three guards removed), defect 9 (raw `*` in `Ledger`), hash-map iteration, `System.nanoTime`.

**Gate M2 (2026-10-12): passed.**
- [x] `./gradlew nightly`: all 9 engine properties at 100,000 tries, 0 failures (558 s for the property class, 9m 41s total run).
- [x] Differential equal after every command (property + 200×150-op seeded stream test).
- [x] Engine coverage 99.1% lines / 97.7% branches (gate 90/85, enforced by `jacocoTestCoverageVerification` in `check`).
- [x] ArchUnit rules green.

Note: M2 landed as one commit ("Milestone 2 completed") rather than one commit per task.

## M3
- [x] M3-T1 Sequencer: Agrona MPSC inbound + control queues, `BackoffIdleStrategy`, `doWork()` with an injected clock, cut on `maxBatch` or interval, `pending ≤ maxBatch` backpressure and replay queue (ADR 0010). Tests: 8 producers × 3,000 commands, none lost or duplicated, per-producer FIFO, drafts == authenticated commands in execution order, last draft root == replay; BUSY on full queue; draft roots equal a validator replay; rollback revokes from the oldest uncommitted trade and replays. `SequencerTest.authFailedCommandNeverEntersBlock` (defect 1) fails when the fix is removed.
- [x] M3-T2 AuditTap (`sim`): Agrona SPSC queue that drops and counts when full → daemon `audit-logger` thread → `PipedOutputStream` → `audit-reader` thread → sink. `AuditTapTest.stalledReaderNeverBlocksEngine` (defect 5): 200k `onTrade` calls finish within 2 s with the reader stalled; it fails (times out) when the pipe write is moved back onto the caller thread.
- [x] M3-T3 Mempool (`sim`): bounded FIFO with `ReentrantLock` + `Condition`, de-duplicated by key while present (`forCommands` keys on `(account, nonce)`), `add` → ADDED/DUPLICATE/FULL, `drain(max, timeoutMs)`. Tests: capacity, duplicates, timeout, wake-up on add, 4 producers × 20k items with a concurrent drainer (none lost, none duplicated).
- [x] M3-T4 JMH harness (`bench`, src/jmh): `ExecuteBench` (throughput + SampleTime; signatures off / Ed25519; deterministic 32,768-command pre-signed workload, all commands accepted, replayed from a copy of the initial state), `BookBench` (addCancel, matchOne; depth 10/100/1000, 4 orders per level). `-Pjmh.quick` runs a 1-fork smoke test; `-Pjmh.includes=<regex>` selects benchmarks.
- [x] M3-T5 Publish baseline: [`docs/bench/baseline.md`](docs/bench/baseline.md) + raw JMH JSON/log. Ryzen 7 4700U laptop, JDK 21.0.12.1, defaults. execute: 3.40 M/s with signatures off (p50 0.4 µs, p99 1.1 µs, p99.9 26 µs); 1,027/s with Ed25519 (p50 0.8 ms). Book addCancel 17.2 → 7.9 M/s from depth 10 to 1000. No optimization done.

**Gate M3 (2026-10-12): passed.** No lost or duplicated commands under 8 producers × 3,000; BUSY on a full queue (`SequencerTest.fullInboundQueueReturnsBusyAndSequencerIsUnaffected`); baseline published with methodology and raw output.

## M4
- [x] M4-T1 FrameCodec: `[len:4][type:1][payload]`, `MAX_FRAME` 1 MiB; tests with 1-byte, every-split-point and random chunking; typed `Messages` round-trip.
- [x] M4-T2 TcpGateway: two virtual threads per connection, bounded ack queue (overflow ⇒ disconnect) and drop-oldest push queue; pre-check injected (`SignaturePreCheck` + shared `CachingVerifier`, ADR 0011); `ExecResult.reasonCode` added.
- [x] M4-T3 ResultCache + QUERY_STATUS: O(1) LRU per account (1,024) and total (1M); AUTH_FAILED never cached. `GatewayTest.retryAfterLostAckReturnsOriginalResult` (defect 8).
- [x] M4-T4 Hardening: max connections, idle + per-frame deadline (slow-loris), per-connection/per-account token buckets ⇒ BUSY, disconnect after 3 protocol errors; `docs/threat-model.md` started. Hardening code landed with T2; T4 added the tests.
- [x] M4-T5 Client + load: `MizanClient`, `LoadGenerator` (`ThreadGroup("sim-traders")`, shared `AtomicLong` ids, HdrHistogram), `mizan node --dev` / `mizan load`.

**Gate M4 (2026-10-12): passed.**
- [x] Framing at every byte boundary (FrameCodecTest).
- [x] Slow-loris safe (HardeningTest: trickling and half-frame clients cut off; server keeps serving).
- [x] Backpressure: a 5,000-command pre-signed burst ⇒ 4,798 BUSY, all answered, max latency 0.48 s, no OOM (gateway pre-check off so the engine's verification is the bottleneck).
- [x] 100 traders × 1,000 orders: 100,000 accepted, 0 errors, conservation holds, `sim-traders` active after join = 0 (807 orders/s; ack p50 114 ms, p99 273 ms on the M3 laptop; Ed25519 verification is the bottleneck).
- [x] 10-minute soak: threads 4 before / 4 after. **First run failed honestly**: heap after GC rose 10 → 137 MiB because bounded caches (signature cache 2^18, result cache 1M) were still filling and GTC orders kept growing the book; also found and fixed a small leak (empty per-account connection sets). Rerun with IOC-only orders and a 50k result cache: heap rose to 60 MiB while caches filled, then stayed flat at 59–61 MiB for the last 4 minutes. The assertion is now "flat in the last third" (< 8 MiB growth); rerun result: 600 s, 99 rounds, 396,000 orders, heap after GC 10 → 60 MiB while filling, then 60–61 MiB for the last 7 samples, threads 4/4, **passed**.

## M5
- [x] M5-T1 Market event queue: `MarketDataService` is an `EngineListener` offering to an Agrona SPSC queue (overflow counted, never blocks).
- [x] M5-T2 MarketDataThread + DepthBook: `marketdata` thread owns a `DepthBook` built only from deltas. `MarketDataTest.depthBookEqualsOrderBookDepth`: 100 streams × 150 commands, equal after every command.
- [x] M5-T3 UDP publisher/subscriber: full-state top-35 DEPTH packets (1,135 bytes), TRADE/REVOKE/HEARTBEAT, unicast subscriptions, TCP SNAPSHOT via the gateway (ADR 0012).
- [x] M5-T4 Minimal JavaFX ladder: `mizan-ui` with the openjfx plugin; `LadderModel` (unit-tested) + `DashboardApp` (coalesced `Platform.runLater`).

**Gate M5 (2026-10-12):**
- [x] Subscriber converges to the exact top-35 depth through a proxy with 10% loss, 5% duplication and 0–20 ms reordering (1,062 packets, 110 dropped, 71 gaps detected, 35 snapshots).
- [x] `DepthBook == OrderBook.depth()` on random streams.
- [ ] "The UI shows a live book under load and never blocks the FX thread": **needs a human to watch it.** The UI is built so that updates are coalesced and the FX thread never does I/O, but nobody has looked at the window yet. Run `mizan node --dev`, `mizan load`, and `./gradlew :mizan-ui:run`.

## M6
- [x] M6-T1 Blocks: canonical header (125 bytes), block, cert (with round, ADR 0013), vote sign-bytes, `ValidatorSet`; round-trip, 2,000-flip tamper detection, quorum tests.
- [x] M6-T2 Genesis loader: strict Jackson JSON (unknown/missing fields rejected) + genesis anchor.
- [x] M6-T3 BlockStore: `[len][crc32c][payload]`, fsync per append, torn tail truncated at every cut point, mid-log corruption refused.
- [x] M6-T4 SnapshotStore: temp + fsync + atomic rename, keep 2, corrupt snapshots skipped.
- [x] M6-T5 Recovery: newest valid snapshot + replay with cert, link, txRoot and root checks; refuses a snapshot beyond the log.
- [x] M6-T6 ConsensusEngine / SingleNodeConsensus.
- [x] M6-T7 Commit path + FINAL: validate on a copy → append+fsync → adopt → snapshot → Committed → FINAL; result cache rebuilt from replayed blocks on start.
- [x] M6-T8 Speculative/committed split + rollback: draft epochs; sequencer replays in-flight + pending. `PersistentNodeTest.revokeStartsAtOldestUncommittedTrade` (defect 6).
- [x] M6-T9 Node wiring: `mizan genesis --out g.json`, `mizan node --genesis g.json --data dir --key dev:0`; graceful shutdown.
- [x] M6-T10 Crash test: child JVM killed at before-append, mid-append, after-append-before-snapshot, mid-snapshot, mid-block and at random; after every kill, recovery is checked against an independent full replay and every client FINAL.

**Regression tests verified (2026-10-12):** `scripts/verify-regressions.sh` plants defects 1 (sequencer and block validity), 2, 3, 4, 5, 6, 8, 9, plus a hash-map iteration and a clock read, back into the code. Each makes its named test **fail**, not merely stop compiling. Earlier one-off checks in M2/M3/M4 only grepped for "BUILD FAILED", which a compile error could also produce; this script supersedes them.

**Gate M6 (2026-10-12): passed.** `./gradlew :mizan-node:recoveryTest -Piter=200`: 200 iterations, all passed. Kills per fault: after-append-before-snapshot 33, before-append 34, mid-append 34, mid-block 33, mid-snapshot 33, random 33. The fault point was actually reached in all but one mid-append iteration, which was killed after 60 s instead. Final height 3,432; 30,901 torn bytes truncated over the run; every client FINAL in the logs was found at its reported height with its order id. (The reported 4,664,500 checks are cumulative: each iteration re-checks all earlier logs.) An independent replay from genesis matched the recovered root after every kill.

## M7
- [x] M7-T1 `docs/protocol.md`: rules L11–L65, timeouts, locking, evidence, safety/liveness arguments, deviations. **Review by a second person is still outstanding.**
- [x] M7-T2 SPI + simulator: `Network`, `Clock`, `Scheduler`; `SimWorld` (single-threaded, `(time, order)`, seeded, crash incarnations), `SimNetwork` (loss, duplication, delay/reorder, partitions, link cuts).
- [x] M7-T3 Messages: PROPOSAL / VOTE / COMMIT codecs with signatures; fuzzed (only `ProtocolException`).
- [x] M7-T4 `RoundBft`: single-threaded event loop, same code in the simulator and over TCP; fixed proposer (validator 0).
- [x] M7-T5 `ConsensusWal`: append-only, fsynced before any message; torn tail ignored, earlier corruption refused, compaction with Windows-safe rename. Re-sends an already-signed vote, never a different one (ADR 0014). `walPreventsEquivocationAfterRestart` includes a negative control (no WAL ⇒ equivocation detected).
- [x] M7-T6 Block validity: `BlockValidator` (§6.8 table). `BlockValidityTest.authFailedInBlockIsInvalid` (defect 1), `oldBlockValidDuringSync` (defect 2); `CachingVerifier` shared with the gateway.
- [x] M7-T7 `TcpNetwork`: persistent peer links with reconnect/backoff and bounded drop-oldest queues. `BftDriver` runs consensus on the `consensus` thread. **4-validator localhost TCP smoke test passes** (all replicas at the same height and root).
- [x] M7-T8 Cluster + evidence: `mizan cluster --nodes 4`; equivocation logged to `evidence.log`. Docker compose was not built (optional in the spec).
- [x] M7-T9 Sweeps: HAPPY, CRASH (validator 2 down then restarted), BYZANTINE (validator 3 double-votes), LOSS (10–30% loss, 5–50 ms jitter, 5% duplication, then heal), RESTARTS (validator 1 crash/restart ×40).

**Gate M7 (2026-10-12): passed.** `./gradlew :mizan-sim:simSweep -Pseeds=1000`: 1,000 seeds × 5 scenarios, all passed: no conflicting commits, and every honest validator advanced ≥ 5 heights after the last fault healed. Message counts: HAPPY 0.98M, CRASH 7.4M, BYZANTINE 2.9M, LOSS 1.7M, RESTARTS 7.2M; 79,039 Byzantine equivocations logged. **An earlier run passed vacuously** (targets were reached before faults fired). That was caught by identical HAPPY/CRASH message counts, and fixed with block pacing and a "progress after heal" stop condition before the run above. TCP smoke test passes.

## M8
- [x] M8-T1 Fault matrix in the simulator with a rotating proposer: PROPOSER_CRASH (crash at 0–12 ms after round start), PARTITION_2_2, LATE_JOINER, LOSS, BYZANTINE.
- [x] M8-T2 Forwarding: gateway commands go to a `CommandPool` and are gossiped (`TX`) to all validators, re-gossiped until committed; de-duplicated by `(account, nonce)` (ADR 0015).
- [x] M8-T3 Rotating proposer `(h + r) mod n`; optimistic execution only on the proposer at proposal time; `REVOKE` when its proposal loses.
- [x] M8-T4 Bulk sync: `SYNC_REQ`/`SYNC_RESP` (≤ 64 blocks), certificate + sync-mode replay checks, 60 s ban for peers serving invalid blocks.
- [x] M8-T5 UDP heartbeats `{node, height, head}` every 500 ms; advisory only. Test: heights propagate under 50% loss.
- [x] M8-T6 `ValidatorNode` + `mizan cluster` (rotating by default, `--fixed-proposer` keeps the M7 path).
- [x] M8-T7 `RotatingClusterTest`: 4 validators over TCP, 8 traders spread over all gateways, validator 1 killed mid-run (clients fail over and retry with the same nonces), restarted, catches up by sync.
- Fix found on the way: shutdown could interrupt an fsync so the recovered height exceeded the reported one; `ChainDriver` now drains with a poison pill and `BftDriver` closes via a flag.

**Gate M8 (2026-10-12): passed.** `./gradlew :mizan-sim:simSweep -Pseeds=1000` on the final code: fault matrix 1,000 seeds × {PROPOSER_CRASH, PARTITION_2_2, LATE_JOINER, LOSS, BYZANTINE}, all passed; proposer-crash delays covered {0, 1, 2, 3, 5, 8, 12} ms (123–160 runs each); late joiners synced 9,560 blocks in total. The M7 sweep rerun in the same build also passed (1,000 × 5; 88,138 equivocations logged; message counts differ from the M7 run because block pacing changed). `RotatingClusterTest`: 160 client FINALs, each equal to the chain's (height, order id); all 4 replicas have the same head at the common height.

## M9
- [x] M9-T1 Metrics: `core.Metrics` SPI (counters, timers, gauges; `NOOP` for tests). `NodeMetrics` keeps counters in `LongAdder`s and timers in HdrHistogram `Recorder`s. `mizan node|cluster --metrics-port` serves plain-text `/metrics` on loopback through the JDK `HttpServer`. Recorded: gateway ingest→ack and ingest→final, sequencer execute and cut→commit, consensus height latency, inbound/pool/awaiting depths, BUSY, pre-check rejects, rollbacks, REVOKEs, round bumps, equivocations, decisions. Tests: `MetricsTest` (quantiles, a live node scraped over HTTP), `SequencerTest` (rollback count, cut→commit time on the fake clock).
- [x] M9-T2 Structured logging: the node, chain and net lifecycle use SLF4J 2 key-value events. The message is an event name (`node_start`, `recovered`, `node_stop`, `committed`, `round_bump`, `synced`, `peer_banned`, `equivocation`, `revoke`, `rollback`, `invalid_block`, `peer_link_up/down`, `consensus_halted`) and the details are pairs. TEXT format by default (`key="value"`); `-Dmizan.log.format=JSON` uses Logback's `JsonEncoder`. Empty blocks are logged at DEBUG only. `RoundBft.Observer` gained `synced`/`peerBanned`, so the simulator stays log-free; the engine still never logs (ArchUnit). Test: `LoggingTest`.
- [x] M9-T3 (part 1) `docs/threat-model.md` completed: trust boundaries; gateway, validator-network, market-data, supply-chain and logging tables; known gaps. Writing it found **two real gaps, now fixed**:
  - Peer links were unauthenticated, so anyone reaching a peer port could claim any validator index for the unsigned messages. That allowed getting an honest validator banned from sync with forged `SYNC_RESP`, and unthrottled `SYNC_REQ` amplification. Fixed with a challenge-response handshake signed with the validator key, connection-bound identity, and at most one sync response per peer per 100 ms.
  - The market-data subscriber map had no size cap (now 1,024).

  Also added: `SecurityRulesTest` (ArchUnit over all production classes: no Java serialization, no reflection or process execution), Dependabot plus dependency-graph submission, and planted regressions "peer auth" and "md subscriber cap" (both detected). A 200-seed sweep of both matrices passes with the sync rate limit.

## M10
See spec §8.

## Known issues / deferred
- M5 UI not visually verified (see Gate M5).
- Result-cache rebuild covers only blocks replayed after the newest snapshot (ADR 0013).
- A corrupted block-log length field pointing past EOF is indistinguishable from a torn tail (ADR 0013).
