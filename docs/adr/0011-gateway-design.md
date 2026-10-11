# ADR 0011: Gateway threading, pre-check placement and dev-mode chain

**Context.** §4.2 forbids `net → crypto` and `net → engine`, yet §M4-T2 asks the gateway to pre-check signatures with the `CachingVerifier`. Listener callbacks arrive on the sequencer and consensus threads, which must never block. `FINAL` needs a chain layer that only arrives in M6.

**Decision.**
- The gateway takes a `TcpGateway.PreCheck` function. The node supplies `SignaturePreCheck`: a key directory learned from accepted `REGISTER` results, plus the shared `CachingVerifier`. An engine re-verification is then a cache hit, and junk is answered `REJECTED` (reason 4) without reaching the sequencer.
- Each connection gets two virtual threads (reader, writer) and two bounded queues. Acks are never dropped; if the ack queue overflows, the client gets `ERR SLOW_CONSUMER` and is disconnected. `TRADE`/`REVOKE`/`SNAPSHOT` pushes use drop-oldest. Callbacks only enqueue.
- Rate limiting answers `BUSY` (same semantics as a full sequencer queue: retry with the same nonce).
- A command that comes back `AUTH_FAILED` is never cached, because it was not executed. If an earlier submission with the same key was executed, the client gets that earlier outcome.
- `ExecResult` gained `reasonCode` (the stable codes of ADR 0007) so `REJECTED` frames carry it.
- Until M6, `mizan node --dev` uses `InstantChain`, which commits each draft immediately on a `consensus` thread, in memory and without fsync. It is clearly labelled and kept only for in-memory tests.

**Consequences.** The gateway is testable with a fake `CommandSink`, so `net` tests stay free of engine code. Pre-check misses (an unknown account) are rejected at the edge, matching what the engine would decide.
