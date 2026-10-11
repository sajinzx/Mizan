# ADR 0010: Sequencer batching, backpressure and rollback replay

**Context.** §6.4 says to cut a draft when `pending ≥ maxBatch` and to stop polling `inbound` at `pending ≥ 2·maxBatch`. A draft carries the speculative state root *after* its commands. If more than `maxBatch` commands have executed when a draft is cut, the root after only the first `maxBatch` is no longer available without re-executing them.

**Decision.**
- `pending` never exceeds `maxBatch`. When it reaches `maxBatch` the sequencer cuts immediately. If `maxInflight` drafts are already waiting for commit, it stops reading `inbound` until a `Committed` arrives. That is the backpressure, and once the bounded inbound queue fills, gateways get `BUSY`. Each draft is all of `pending`, so its root is simply the current speculative root.
- `Rollback(freshCopy, toReplay)` emits `onRevoke(oldest uncommitted firstTradeSeq)` (defect 6), swaps in `freshCopy`, and queues `toReplay`. `doWork` re-executes the queued commands ahead of `inbound` under the same batch bound, so a long replay also respects `maxBatch` and `maxInflight`.
- `doWork()` is public and takes an injected `LongSupplier` clock, so tests drive the sequencer single-threaded and deterministically. `start()` runs it on the dedicated `sequencer` thread with `BackoffIdleStrategy`.
- Agrona 2.x needs `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`. The build adds it to every test JVM, and JMH and the node launcher must add it too.

**Consequences.** At most `maxBatch · (maxInflight + 1)` commands are executed but uncommitted at any time, which bounds what a rollback can revoke. `Sequencer` and its nested types are exempt from the determinism rules (it owns a thread and a clock); everything it calls is not.
