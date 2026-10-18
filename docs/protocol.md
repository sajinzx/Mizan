# Consensus protocol (Tendermint-lite)

Status: written before the M7 implementation, as §6.8 requires. **Review by a second person is still outstanding** (M7-T1 asks for one). Until then, treat this document as the author's specification.

Source: E. Buchman, J. Kwon, Z. Milosevic, *The latest gossip on BFT consensus* (2018), Algorithm 1. Line references `L…` below refer to that algorithm. Mizan implements it with the deviations listed at the end.

## Model

- `n = 3f + 1` validators with static Ed25519 keys from genesis; at most `f` are Byzantine. A quorum is `2f + 1` (any two quorums share at least one honest validator).
- The network is partially synchronous: after an unknown Global Stabilization Time, messages between honest validators arrive within an unknown bound Δ. Before that, they may be lost, delayed, duplicated or reordered. Gossip retransmission (below) turns "eventually delivered after GST" into reality for lost messages.
- Each validator runs one **single-threaded event loop**. Its inputs are messages, timeouts, and "the application has a value ready". The same code runs under the simulator and over TCP.

## Values and validity

A value is a `Block` (§5.7), and `id(v)` is its block hash. `valid(v)` is the table in §6.8:

| Check | Live voting | Sync / replay |
|---|---|---|
| version, chainId, `height == h`, `prevHash == hash(block h−1)` (genesis anchor for `h = 1`) | ✓ | ✓ |
| proposer field equals `proposer(h, r)` | ✓ | ✓ (r from the cert) |
| `txRoot` matches commands; command count within limits | ✓ | ✓ |
| every command decodes; replay on a *copy* of the committed core has **no `AUTH_FAILED`** (defect 1) and ends at `header.stateRoot` | ✓ | ✓ |
| `timestamp > timestamp(block h−1)` | ✓ | ✓ |
| `|timestamp − localNow| ≤ 5 s` | ✓ | ✗ (defect 2) |
| commit certificate: ≥ 2f+1 distinct valid precommit signatures for `(h, cert.round, id(v))` | — | ✓ |

## Messages

All messages are signed by their sender over canonical bytes and carry the sender's validator index.

- `PROPOSAL(h, r, validRound, block)`: signed bytes `"CBPROP1" || chainId || h || r || validRound || id(block)`.
- `PREVOTE(h, r, id | nil)` and `PRECOMMIT(h, r, id | nil)`: vote sign-bytes of §5.7; nil signs 32 zero bytes.

M8 adds unsigned messages whose content authenticates itself: `COMMIT(block, cert)` and `SYNC_RESP(blocks)` carry certificates, `TX` carries a client-signed command, and `SYNC_REQ(fromHeight, max ≤ 64)` is a request. Their sender index comes from the **authenticated peer link** (M9-T3). On connect, the acceptor sends a 32-byte random challenge. The dialer answers with its index and an Ed25519 signature, made with its validator key, over `chainId || "MZPEER1" || to || from || challenge`. Every later frame on that link must carry the same index. A validator serves at most one `SYNC_RESP` per peer per 100 ms (≤ 64 blocks, ≤ 1 MiB). It ignores a peer for 60 s once that peer serves a block that fails certificate or replay checks.

A message is dropped, never acted on, if its signature or validator index is invalid, its height is not the current one (except one height ahead, which is buffered), or it duplicates one already recorded.

## State (per validator)

`h` (current height), `r` (current round), `step ∈ {PROPOSE, PREVOTE, PRECOMMIT}`, `lockedValue / lockedRound` (−1), `validValue / validRound` (−1), the message log for height `h`, and `decision[h]`.

## Proposer selection

`proposer(h, r) = (h + r) mod n` (§6.8). M7 runs a **fixed proposer** (validator 0, which hosts the sequencer). M8 switches to rotation. Both use the same function, parameterized.

## Rules

| # | Upon | Condition | Action |
|---|---|---|---|
| L11 | `StartRound(r)` | — | `round ← r`, `step ← PROPOSE`. If I am `proposer(h, r)`: propose `validValue` if set (with `validRound`), else a new value from the application. Otherwise schedule `OnTimeoutPropose(h, r)`. |
| L22 | `PROPOSAL(h, r, v, −1)` from the proposer | `step = PROPOSE` | PREVOTE `id(v)` if `valid(v)` and (`lockedRound = −1` or `lockedValue = v`), else PREVOTE nil. `step ← PREVOTE`. |
| L28 | `PROPOSAL(h, r, v, vr)` and 2f+1 `PREVOTE(h, vr, id(v))` | `step = PROPOSE`, `0 ≤ vr < r` | PREVOTE `id(v)` if `valid(v)` and (`lockedRound ≤ vr` or `lockedValue = v`), else nil. `step ← PREVOTE`. |
| L34 | 2f+1 `PREVOTE(h, r, *)` | `step = PREVOTE`, first time | schedule `OnTimeoutPrevote(h, r)` |
| L36 | `PROPOSAL(h, r, v, *)` and 2f+1 `PREVOTE(h, r, id(v))` | `valid(v)`, `step ≥ PREVOTE`, first time | if `step = PREVOTE`: lock (`lockedValue ← v`, `lockedRound ← r`), PRECOMMIT `id(v)`, `step ← PRECOMMIT`. Always: `validValue ← v`, `validRound ← r`. |
| L44 | 2f+1 `PREVOTE(h, r, nil)` | `step = PREVOTE` | PRECOMMIT nil, `step ← PRECOMMIT` |
| L47 | 2f+1 `PRECOMMIT(h, r, *)` | first time | schedule `OnTimeoutPrecommit(h, r)` |
| L49 | `PROPOSAL(h, r', v, *)` and 2f+1 `PRECOMMIT(h, r', id(v))` | `decision[h]` unset, `valid(v)` | **decide** `v` with those precommits as its certificate; `h ← h+1`; reset locks and the log; `StartRound(0)` |
| L55 | f+1 messages of any type at `(h, r')` with `r' > r` | — | `StartRound(r')` (round skipping) |
| T1 | `OnTimeoutPropose(h, r)` | still `(h, r, PROPOSE)` | PREVOTE nil, `step ← PREVOTE` |
| T2 | `OnTimeoutPrevote(h, r)` | still `(h, r, PREVOTE)` | PRECOMMIT nil, `step ← PRECOMMIT` |
| T3 | `OnTimeoutPrecommit(h, r)` | still `(h, r)` | `StartRound(r + 1)` |

Timeouts grow with the round: `timeout(r) = proposeTimeoutMs + r · timeoutDeltaMs`. Prevote and precommit timeouts use the same formula.

## Safety argument (why no two honest validators decide differently at one height)

1. **Within a round:** deciding `v` at round `r` needs 2f+1 PRECOMMIT for `id(v)`. An honest validator precommits at most one non-nil value per round (it precommits only once per round, by `step`). Two quorums intersect in an honest validator, so no other value can also gather 2f+1 precommits at `r`.
2. **Across rounds (locking):** if `v` is decided at round `r`, at least f+1 honest validators locked `v` at `r` (they precommitted it after seeing 2f+1 prevotes). An honest locked validator prevotes another value `v' ≠ v` in a later round `r' > r` only if it sees a proposal for `v'` with `validRound vr ≥ r` justified by 2f+1 prevotes at `vr` (L28). By induction no such quorum for `v' ≠ v` can exist at any round ≥ `r`: it would need prevotes from some of the f+1 locked honest validators. So `v'` never gets 2f+1 prevotes, hence never 2f+1 precommits.
3. **Across crashes:** an honest validator persists `(h, r, step, lockedRound, lockedValue, last signed vote)` **before** sending any message (`ConsensusWal`, fsync + atomic rename). After a restart it refuses to sign a different vote for an `(h, r, type)` it already signed, and restores its lock. Without this, a restart could make an honest node equivocate and break (1) and (2).

## Liveness argument (eventual progress after GST)

After GST with an honest proposer, all honest validators receive the proposal and each other's votes within Δ. Timeouts grow linearly with the round, so eventually `timeout(r) > ` the time needed for a round. The `validValue` rule (L36, L11) guarantees that a value which already has a prevote quorum is re-proposed, so honest validators locked on it can prevote it (L28). The proposer rotates (M8), so a faulty proposer stalls at most one round in `n`. With the M7 **fixed proposer**, liveness requires validator 0 to be honest and up; M7 tests therefore crash a non-proposer, and proposer failover is M8.

Lost messages: every validator re-broadcasts its current-round messages (its proposal or votes) every `proposeTimeoutMs / 2` while the height is undecided. Together with timeouts, this keeps the protocol live under message loss. In the paper, gossip plays this role.

## Evidence

Two validly signed votes from one validator for the same `(h, r, type)` with different block hashes are **equivocation evidence**. They are logged (validator index, height, round, type, both hashes) and counted. Evidence is not slashed; there are no stakes.

## Commit and finality

On decide, the validator persists `block || cert` (fsync), applies it to its committed core, and notifies the sequencer (`Committed`, if the block is its own draft) and the gateway (`FINAL`). A block is final once it has 2f+1 precommits *and* is fsynced locally (ADR 0002).

## Deviations from the paper

- Retransmission of current-round messages replaces the paper's assumed reliable gossip.
- Messages for height `h + 1` are buffered (one height ahead) rather than gossiped later; lagging nodes catch up through block sync (M8), which verifies commit certificates.
- No vote extensions, no evidence handling beyond logging, a static validator set, and no light-client headers beyond `CommittedBlock`.
- **Re-sending a signed vote after a restart:** if the WAL shows a vote already signed at the current `(h, r, type)`, the validator re-sends that exact vote instead of evaluating the rule again. It never signs a different vote there, and never signs for an earlier position (ADR 0014).
- **Empty blocks:** when the proposer has no sequencer draft for a while, it proposes an empty block so that heights keep advancing (ADR 0014).
- **Simulator-only signature scheme:** seed sweeps sign messages with a fast stand-in (`SHA-512(pk || msg)`, ADR 0014) so that 1,000-seed matrices finish in minutes. The Byzantine model in the simulator only signs as itself, so the stand-in's forgeability does not weaken what the sweeps show. TCP runs and unit tests use real Ed25519.
- **Timestamps of re-proposed blocks:** a re-proposal of the valid block keeps its original timestamp, and a validator checks proposals against its clock (±5 s) in live mode. Each validator caches its verdict per block hash for the height, so the validators whose prevotes formed the polka still accept the re-proposal. A validator that first sees it more than 5 s late (one that just restarted, say) prevotes nil. With at most f such validators, the quorum is unaffected. Beyond f, this check could delay liveness until the next height's fresh proposal is possible; it never affects safety.
