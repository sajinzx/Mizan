# ADR 0001: Single-writer sequencer owns all trading state

**Context.** Matching and ledger updates must be deterministic so every node computes the same state root (§5.6). Locks or concurrent mutation would make execution order depend on scheduling.

**Decision.** Exactly one thread owns each `ExecutionCore`. The `sequencer` thread owns the speculative core and the `consensus` thread owns the committed core. Every other thread talks to them only through bounded queues: an MPSC inbound queue, a control queue, and SPSC event queues. The engine itself never touches a clock, randomness, threads, I/O, or logging (§2).

**Consequences.** Throughput is bounded by one core, and instruments are not sharded (listed in the README limitations). Engine code needs no synchronization, and replaying the same command log always gives the same state. A full inbound queue returns `BUSY` rather than blocking.
