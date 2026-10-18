# Changelog

All notable changes, by milestone. Gate evidence and honest caveats live in [PROGRESS.md](PROGRESS.md).

## 1.0.0 — 2026-10-12

First complete release of the core and extended tiers.

### Engine (M1–M3)
- Deterministic price-time-priority order book: limit, market (collared IOC), IOC, FOK, post-only, and self-trade prevention.
- Escrowed multi-asset ledger with conservation, maker/taker fees, a 2^62 supply cap and overflow-checked money arithmetic (ADR 0008).
- Canonical command codec, Ed25519 signatures over `chainId`, and per-account nonces burned even on business rejects.
- Single-writer sequencer with bounded lock-free queues, batching, backpressure and rollback/replay (ADRs 0001, 0010).
- ArchUnit determinism rules: no clock, randomness, I/O, threads, logging, floating point or hash-ordered iteration in the engine.
- Differential testing against a reference engine, jqwik properties (100k tries nightly), and planted-regression verification.

### Node, gateway, market data (M4–M5)
- TCP gateway on virtual threads with idempotent retries (ADR 0003), a signature pre-check, token buckets, slow-loris deadlines and bounded queues (ADR 0011).
- UDP market data: full-state top-35 depth packets, trades, revokes and heartbeats, plus a TCP snapshot (ADR 0012); JavaFX dashboard.

### Persistence (M6)
- CRC32C block log as the write-ahead log, snapshots, torn-tail truncation, refusal of mid-log corruption, and recovery verified by 200 kill/restart iterations (ADRs 0002, 0013).

### Consensus (M7–M8)
- Tendermint-lite BFT with a single-threaded event loop shared between the deterministic simulator and TCP. Votes are persisted to an fsynced WAL before sending, and equivocation evidence is logged (ADR 0014, `docs/protocol.md`).
- Rotating proposer, command gossip, optimistic execution on the proposer with `REVOKE`, certificate-checked bulk sync, and UDP heartbeats (ADR 0015).
- Seeded fault sweeps: 1,000 seeds × {crash, Byzantine, loss, restarts, proposer crash, 2|2 partition, late joiner, mangled messages}.

### Hardening (M9)
- Metrics (`/metrics`, HdrHistogram) and structured key-value / JSON logging.
- Authenticated peer links (challenge-response with validator keys), sync rate limits, and a cap on market-data subscribers.
- Threat model, runbook, plus `verify-chain`, `keygen` and `faucet`.
- Structure-aware fuzzing of consensus messages, blocks and a live `RoundBft`.
- Chain panel, depth chart, REVOKE banner and multi-instrument UI.
- 30-minute chaos run on four validator JVMs.

### Optimization (M10)
- State root v2: a persistent sparse Merkle tree with dirty-key batching (ADR 0016). It is 9.6× faster per block at 10,000 accounts and 70× at 100,000; at 100 accounts it is 3.5× slower.
- Intrusive O(1)-cancel price levels and a primitive-keyed order index: +26% on cancels in deep queues, otherwise within noise.
- The Ed25519 verifier reuses decoded keys and `Signature` objects: no measurable change.
- GC comparison and signature-verification benchmarks. All numbers are in `docs/bench/m10.md`.

### Sandbox terminal
- `./gradlew :mizan-ui:run` starts 4 validators, market makers and a shadow replica in one window.
- The window shows the order book, candles, tape, your orders' finality, the validators, the peer links and live guarantees, and has fault controls (ADR 0017).
