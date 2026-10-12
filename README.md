# Mizan

A deterministic limit-order-book engine with an escrowed multi-asset ledger. Commands are Ed25519-signed, and fills become final only once committed to an fsynced block log. A Tendermint-lite BFT layer is planned for the extended tier. **Educational project: simulated assets only.**

> Status: **M2 (deterministic engine) complete; M3 next.** Nothing here has been benchmarked yet. Performance numbers will appear only with linked raw output in `docs/bench/`.

## Quick start

Requires any JDK that Gradle 9.8 can run on (17+). The Java 21 toolchain is downloaded automatically if it is not installed.

```sh
./gradlew build            # compile, format check, Error Prone, tests, coverage, module-graph check
./gradlew nightly          # property tests at 100k tries (+ tag 'slow')
./gradlew recoveryTest     # crash/restart tests (-Piter=N)
./gradlew simSweep         # seeded consensus fault sweeps (-Pseeds=N)
```

Randomized tests print `SEED=<n>` and a replay command on failure. Re-run with `-Pseed=<n>`.

## Documents

- [Implementation spec](implementation-claude-code.md): design, contracts, milestones, gates
- [Progress](PROGRESS.md)
- [Assumptions](docs/assumptions.md)
- [Consensus protocol](docs/protocol.md) · [Threat model](docs/threat-model.md) · [Benchmark baseline](docs/bench/baseline.md)
- Architecture decisions:
  - [0001 Single-writer sequencer](docs/adr/0001-single-writer.md)
  - [0002 Block log is the WAL](docs/adr/0002-block-log-is-wal.md)
  - [0003 Nonce idempotency + result cache](docs/adr/0003-nonce-idempotency.md)
  - [0004 Project name](docs/adr/0004-project-name.md)
  - [0005 Toolchain versions](docs/adr/0005-toolchain-versions.md)
  - [0006 Test-scope testkit edges](docs/adr/0006-test-scope-testkit-edges.md)
  - [0007 Core type placement](docs/adr/0007-core-type-placement.md)
  - [0008 Engine semantics clarifications](docs/adr/0008-engine-semantics-clarifications.md)
  - [0009 Test signer and architecture scans](docs/adr/0009-test-signing-and-arch-scans.md)
  - [0010 Sequencer batching and backpressure](docs/adr/0010-sequencer-batching.md)
  - [0011 Gateway design](docs/adr/0011-gateway-design.md)
  - [0012 Market data](docs/adr/0012-market-data.md)
  - [0013 Persistence and recovery](docs/adr/0013-persistence-and-recovery.md)
  - [0014 BFT implementation](docs/adr/0014-bft-implementation.md)
  - [0015 Rotation, forwarding, sync](docs/adr/0015-rotation-forwarding-sync.md)

## Modules

`core` · `crypto` · `engine` · `store` · `chain` · `net` · `node` · `testkit` · `sim` · `ui` · `bench`. The allowed dependencies between them are enforced by `./gradlew checkModuleGraph` (spec §4.2).

## Limitations

Simulated assets only; no real-money custody or compliance. Replace-order is cancel-and-new and loses queue priority. Single sequencer, with no instrument sharding. The full list arrives with M10.
