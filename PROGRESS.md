# Progress
Current milestone: M3   Last gate passed: M2 (M1 gate passed except the planted-red CI check)
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
- [ ] M3-T3 Mempool
- [ ] M3-T4 JMH harness
- [ ] M3-T5 Publish baseline

## M4
- [ ] M4-T1 FrameCodec
- [ ] M4-T2 TcpGateway
- [ ] M4-T3 ResultCache + QUERY_STATUS
- [ ] M4-T4 Hardening
- [ ] M4-T5 Client + load

## M5
- [ ] M5-T1 Market event queue
- [ ] M5-T2 MarketDataThread + DepthBook
- [ ] M5-T3 UDP publisher/subscriber
- [ ] M5-T4 Minimal JavaFX ladder

## M6
- [ ] M6-T1 Blocks
- [ ] M6-T2 Genesis loader
- [ ] M6-T3 BlockStore
- [ ] M6-T4 SnapshotStore
- [ ] M6-T5 Recovery
- [ ] M6-T6 ConsensusEngine / SingleNodeConsensus
- [ ] M6-T7 Commit path + FINAL
- [ ] M6-T8 Speculative/committed split + rollback
- [ ] M6-T9 Node wiring
- [ ] M6-T10 Crash test

## M7–M10
See spec §8; expand here when M6's gate passes.

## Known issues / deferred
- JavaFX plugin application deferred to M5-T4.
