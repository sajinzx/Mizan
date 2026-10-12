# Threat model

**Scope.** Mizan is an educational exchange that trades simulated assets; no real money is involved. The goal is that a hostile client, peer or minority of validators can neither corrupt shared state nor cheaply deny service to well-behaved clients. Each mitigation names the code that implements it. Where a claim can be checked mechanically, the checking test is named too.

**Out of scope.** Physical access to a validator host. A compromised JDK or OS. More than `f` Byzantine validators. Volumetric network DoS (SYN floods and similar). Confidentiality: all orders are public by design, since every validator replays every command.

## Assets

| Asset | Property that matters |
|---|---|
| Ledger and book state | Integrity: conservation, escrow, FIFO, no double execution |
| Account identity | Only the holder of an account's key can act for it |
| Validator identity | Only the holder of a validator key can vote, propose, or speak on a peer link |
| Committed chain | Agreement: no two honest validators commit different blocks at a height |
| Node availability | The single sequencer thread and the consensus loop are the bottlenecks to protect |

## Trust boundaries

1. **Client → gateway** (TCP, port 9000 by default). Fully untrusted.
2. **Validator ↔ validator** (TCP peer links and UDP heartbeats). Peers are authenticated with validator keys. Up to `f` of them may be Byzantine.
3. **Node → market-data subscribers** (UDP, loopback by default). Subscribers are untrusted, and the feed is public.
4. **Operator → node** (CLI, genesis file, key files, data directory). Trusted, but inputs are still validated.

## Client-facing gateway

| Threat | Mitigation | Where / test |
|---|---|---|
| Forged or tampered commands | Ed25519 signature over the canonical envelope. The engine verifies every command against the registered key | `ExecutionCore.authenticate` |
| Cross-chain replay | `chainId` is inside the signed bytes | `CommandCodec`; `ExecutionCoreTest` |
| Replay of an old command | Strictly increasing per-account nonce, burned even on business rejects | `Ledger.consumeNonce` |
| Double execution on retry | `(account, nonce)` idempotency plus `ResultCache` | ADR 0003; `GatewayTest.retryAfterLostAckReturnsOriginalResult` |
| Signature-verification CPU exhaustion of the sequencer | Pre-check on gateway virtual threads with a shared `CachingVerifier`, so junk never reaches the sequencer | `SignaturePreCheck`; metric `gateway_precheck_rejected` |
| An `AUTH_FAILED` command smuggled into a block | The sequencer never appends it; validators reject blocks that contain one | defect 1; `SequencerTest`, `BlockValidityTest` |
| Oversized frames or memory exhaustion | `MAX_FRAME` = 1 MiB, envelope ≤ 256 bytes, bounded per-connection queues | `FrameCodec`, `Limits` |
| Slow-loris (byte trickling) | Per-frame completion deadline plus idle timeout | `DeadlineInputStream`; `GatewayTest` |
| Connection floods | `maxConnections` (default 1,000). Excess connections get `ERR` and are closed | `TcpGateway` |
| Request floods | Per-connection and per-account token buckets → `BUSY`; bounded sequencer queue → `BUSY` | `TokenBucket`, `Sequencer`; metric `gateway_busy` |
| Malformed bytes | Decoders throw only `ProtocolException` (fuzzed); three protocol errors disconnect | `CommandCodec`, `Messages`; codec fuzz tests |
| A slow consumer exhausting server memory | Ack queue overflow disconnects; the push queue drops the oldest entry | `TcpGateway.Conn` |
| Integer overflow in money arithmetic | `Math.multiplyExact`/`addExact` on all quantity × price; supply capped at 2^62 | ADR 0008; `ArchRulesTest.noRawMultiplyOnMoney` |

## Validator network

| Threat | Mitigation | Where / test |
|---|---|---|
| Impersonating a validator on a peer link | Challenge-response handshake. The acceptor sends 32 random bytes; the dialer signs `chainId ‖ "MZPEER1" ‖ to ‖ from ‖ challenge` with its validator key. The connection is then bound to that index, and a frame claiming another index drops the link | `TcpNetwork`, `BftSetup.peerAuth`; `PeerAuthTest` (planted regression "peer auth") |
| Getting an honest validator banned from sync by forging its responses | Prevented by link authentication, since bans key on the authenticated index | `RoundBft.applySync` |
| Forged proposals or votes | Each is signed by the validator and checked against the genesis key for that index | `RoundBft`, `ValidatorSet` |
| Equivocation (double vote or double proposal) | Detected and logged as evidence (`evidence.log`, event `equivocation`). Safety holds with ≤ f equivocators | `docs/protocol.md`; sim BYZANTINE sweep |
| An honest node equivocating after a crash | Votes and locks are fsynced to the consensus WAL before sending; on restart only the same vote is re-sent | ADR 0014; `walPreventsEquivocationAfterRestart` |
| Invalid blocks (bad root, bad tx root, `AUTH_FAILED` inside, too large, bad timestamp) | Full re-execution and checks before voting (`BlockValidator`, §6.8 table) | `BlockValidityTest` |
| Fake history during catch-up | Every synced block needs a certificate with 2f+1 distinct valid signatures and a replay that reproduces its state root. A peer serving an invalid block is ignored for 60 s | `RoundBft.applySync`; sim LATE_JOINER |
| Sync amplification (cheap `SYNC_REQ` → 1 MiB responses) | At most one response per peer per 100 ms, ≤ 64 blocks and ≤ 1 MiB each | `RoundBft.serveSync` |
| Gossip spam | Gossiped commands are client-signed and pass the same checks as gateway commands. The pool is bounded and de-duplicated by `(account, nonce)` | `CommandPool` |
| Malformed peer messages | Decoders throw only `ProtocolException` and the message is ignored | `ConsensusMessage`; M9-T5 fuzz tests |
| Lying heartbeats | Advisory only: they can trigger a sync request but never cause a commit | `UdpHeartbeat`, ADR 0015 |

## Market data

| Threat | Mitigation | Where / test |
|---|---|---|
| A subscription flood growing server memory | Subscriptions expire after 10 s of silence; at most 1,024 live subscribers | `MarketDataService`; `SubscriberCapTest` (planted regression "md subscriber cap") |
| Reflection or amplification via spoofed `SUBSCRIBE` | The socket binds to loopback by default. If exposed, this is a known gap (see below) | `MarketDataService` |
| A stalled consumer blocking the engine | The engine-side queue never blocks; overflow is counted | defect 5; `AuditTapTest` |

## Code and supply chain

| Threat | Mitigation | Where / test |
|---|---|---|
| Java deserialization attacks | No `ObjectInputStream`/`ObjectOutputStream` anywhere; all encodings are hand-written canonical formats | `SecurityRulesTest.noJavaSerialization` |
| Code selected by network input | No reflection, `ProcessBuilder` or `Runtime.exec` in production code | `SecurityRulesTest.noProcessExecutionOrReflection` |
| Vulnerable dependencies | Few dependencies, all pinned in `gradle/libs.versions.toml`. Dependabot opens weekly update PRs, and the resolved graph is submitted to GitHub for advisory alerts. The Gradle wrapper checksum is validated in CI | `.github/dependabot.yml`, `.github/workflows/dependencies.yml`, `ci.yml` |
| Non-determinism splitting validators (hash iteration order, clock, randomness in the engine) | ArchUnit rules on the engine | `ArchRulesTest` (planted regressions "hash iteration", "clock") |

## Logging

| Threat | Mitigation |
|---|---|
| Log injection (forged lines via newlines in attacker strings) | Lifecycle logs are key-value events (M9-T2). Values are numbers, hex prefixes, enum-like internal strings, and exception text from the JDK. The protocol carries no free-text client fields, so no client-chosen string is logged. With `-Dmizan.log.format=JSON`, Logback's `JsonEncoder` escapes every value |
| Secrets in logs | Keys are never logged. Key files hold only a base64 seed and are read once at start |
| Log flooding | Per-block lines are INFO only for non-empty blocks (empty ones are DEBUG). Per-command events are never logged; they are counted in metrics |

## Known gaps

- **No TLS.** Client and peer traffic is plaintext, and peer authentication happens only at connect time. An on-path attacker could hijack an authenticated TCP session. Deploy on localhost or a trusted network.
- **UDP heartbeats and market data are unauthenticated.** Heartbeats are advisory, and market data is public. If the market-data port is bound to a public interface, spoofed `SUBSCRIBE`s can reflect up to about 1,024 streams at a victim.
- Rate limits are per node, not global across a cluster.
- No protection against volumetric (SYN or bandwidth) DoS.
- The dev genesis admin and validator keys are public (`DevGenesis`, `dev:<i>`). Never use them outside local experiments.
- The admin key can mint any amount of an asset up to the 2^62 supply cap (the faucet). It is a single trusted key, by design for a teaching system.
- Equivocation evidence is logged but never punished (there is no slashing or validator-set change).
