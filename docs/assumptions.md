# Assumptions

What Mizan is, and the assumptions its guarantees rest on. Design detail lives in [the implementation spec](../implementation-claude-code.md) and the ADRs in [`docs/adr/`](adr/).

## What the project is

1. A deterministic multi-instrument limit-order-book engine with an escrowed multi-asset ledger.
2. One **sequencer** thread owns all speculative trading state; all other threads talk to it through bounded queues ([ADR 0001](adr/0001-single-writer.md)).
3. Commands are **Ed25519-signed envelopes** with per-account nonces and a `chainId`.
4. The engine returns provisional fills immediately (`ACCEPTED_PROVISIONAL`).
5. Fills become **`FINAL`** only when a block carrying them is committed and **fsynced** ([ADR 0002](adr/0002-block-log-is-wal.md)).
6. **Core tier (M1–M6):** engine, sequencer, TCP gateway, UDP market data, block log, and crash recovery.
7. **Extended tier (M7–M10):** Tendermint-lite BFT over a simulated and a real network, rotating proposers, block sync, gossip, hardening, and optimization.
8. State root = Merkle over the ledger, account meta, resting book, and counters. Every node must compute the same root.
9. Testing is differential (against a naive `ReferenceEngine`), property-based, crash-injected, and fault-sweep seeded.
10. Simulated assets only. No real money, no custody, no hand-written crypto.

## Environment assumptions

- **Fault model (Extended tier):** `n = 3f + 1` validators with at most `f` Byzantine. The network is partially synchronous: messages can be dropped, delayed, duplicated, or reordered, but eventually get through after the network heals.
- **Storage:** `FileChannel.force(true)` returning means the data is on stable storage. A crash can tear only the last record of the block log, which recovery truncates; any other corruption makes recovery refuse to start.
- **Clocks:** wall clocks decide only *when* to cut a block and when timeouts fire. They never affect execution results. Block timestamps are metadata; the ±5 s bound applies only to live voting, never to sync or replay (defect 2).
- **Crypto:** the JDK's Ed25519 and SHA-256 implementations are correct. Public keys are 44-byte X.509 encodings.
- **Clients** retry with the **same nonce** after `BUSY` or a missed ack ([ADR 0003](adr/0003-nonce-idempotency.md)).

## Durability and ack contract

| State | Meaning | Survives crash? |
|---|---|---|
| `BUSY` | Queue full, nothing executed | n/a |
| `REJECTED` | Deterministic reject with reason | Yes, once its block is FINAL |
| `ACCEPTED_PROVISIONAL` | Executed on the speculative core; fills may be **revoked** | No |
| `FINAL` | Block committed (2f+1 precommits) **and fsynced** | Yes |

## Out of scope

Real assets, custody, compliance, network-level DoS protection beyond basic limits, dynamic validator sets, and instrument sharding.
