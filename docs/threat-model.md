# Threat model

Scope: an educational, simulated-asset system (no real money). The goal is that a hostile client or peer can neither corrupt shared state nor deny service to well-behaved clients cheaply. This document grows with each milestone; M9-T3 completes it.

## Assets

- **Ledger and book state**: integrity matters (conservation, escrow, FIFO).
- **Account identity**: only the holder of an account's key can act for it.
- **Node availability**: the single sequencer thread is the bottleneck to protect.

## Client-facing gateway (M4)

| Threat | Mitigation | Where |
|---|---|---|
| Forged or tampered commands | Ed25519 over the canonical envelope; the engine verifies every command against the registered key | `ExecutionCore.authenticate` |
| Cross-chain replay | `chainId` is inside the signed bytes | `CommandCodec`, `authenticate` |
| Replay of an old command | Strictly increasing per-account nonce, burned even on business rejects | `Ledger.consumeNonce` |
| Double execution on retry | `(account, nonce)` idempotency + `ResultCache` | ADR 0003, `TcpGateway` |
| Signature-verification CPU exhaustion of the sequencer | Pre-check on gateway virtual threads, shared `CachingVerifier`; junk never reaches the sequencer | `SignaturePreCheck` |
| Oversized frames / memory exhaustion | `MAX_FRAME = 1 MiB`, envelope ≤ 256 bytes, bounded per-connection queues | `FrameCodec`, `TcpGateway` |
| Slow-loris (byte trickling) | Per-frame completion deadline plus idle timeout | `DeadlineInputStream` |
| Connection floods | `maxConnections`; excess get `ERR` and are closed | `TcpGateway` |
| Request floods | Per-connection and per-account token buckets → `BUSY`; bounded sequencer queue → `BUSY` | `TokenBucket`, `Sequencer` |
| Malformed bytes | Decoders throw only `ProtocolException` (fuzzed); repeated protocol errors disconnect | `CommandCodec`, `Messages` |
| Slow consumer exhausting server memory | Ack queue overflow disconnects; push queue drops the oldest | `TcpGateway.Conn` |
| Java deserialization attacks | No `ObjectInputStream` anywhere; all encodings are hand-written canonical byte formats | — |

## Known gaps (to be revisited)

- No TLS: traffic is plaintext on localhost or a trusted network.
- Rate limits are per node, not global across a cluster.
- No protection against network-level (SYN/volumetric) DoS.
- The dev genesis admin key is public (`DevGenesis`); never use it outside local experiments.
