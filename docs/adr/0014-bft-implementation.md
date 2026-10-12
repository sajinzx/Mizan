# ADR 0014: Tendermint-lite implementation choices (M7)

**Context.** `docs/protocol.md` specifies the algorithm. Turning it into a running system over a simulator and over TCP needed several engineering decisions.

**Decisions.**
- **One event loop per validator.** `RoundBft` is a single-threaded state machine. In the simulator, `SimWorld` delivers every event on one thread in `(virtual time, order)` order. Over TCP, `BftDriver` funnels network messages, timeouts and drafts through one `ScheduledExecutorService` (the `consensus` thread), which also owns the committed core and the block log.
- **Simulator signatures.** Seed sweeps sign with `SHA-512(pk || msg)` (`CommandSigner.fastTag`) rather than Ed25519, so 1,000 seeds × 5 scenarios run in about a minute. Every simulated node, Byzantine ones included, signs only as itself, so the scheme's forgeability is never exercised. TCP runs and unit tests use real Ed25519.
- **Retransmission and catch-up.** Each validator re-broadcasts its own current-height messages every `proposeTimeoutMs / 2`. A validator that receives a message for a lower height replies (rate-limited) with that height's `COMMIT` (block + certificate). The lagging peer verifies the certificate and applies the block in sync mode, which replaces the paper's reliable gossip.
- **Crash safety.** The consensus WAL is an append-only, fsynced log of `SafetyState` (last signed proposal, prevote and precommit, plus the lock), written before any message is sent. A restarted validator that must vote again at an `(h, r)` it already signed re-sends exactly the same vote (Ed25519 is deterministic). It never signs anything for an earlier position. Without the re-send, a restart could stall a round that needs that validator's vote. `ConsensusSimTest.walPreventsEquivocationAfterRestart` checks both directions, with a negative control.
- **Fixed proposer in M7.** Validator 0 hosts the sequencer and is the only proposer. The others are followers with a full replica. BFT mode forces `maxInflight = 1`, so a draft's root is always relative to committed state. When no draft is ready for `max(200 ms, proposeTimeoutMs / 2)`, the proposer proposes an empty block, which keeps height moving and needs no state root change. Rotation, forwarding and failover are M8.
- **Block hashes are cached** (`Block` is a final class, not a record), because consensus asks for them many times per message.
- **Evidence.** Conflicting signed messages from one validator for the same `(h, r, kind)` are logged to `evidence.log` and as a WARN line. There is no slashing.

**Consequences.** M7's liveness depends on validator 0 being up. The sweeps crash validator 2 (not the proposer), restart validator 1 repeatedly, and make validator 3 Byzantine.
