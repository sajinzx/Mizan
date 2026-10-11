# ADR 0009: Fast test signer for property runs; source scans for operator rules

**Context.** The M2 gate runs properties at 100,000 tries, with about 100 commands per try. With real Ed25519, signing and verifying would dominate the runtime by orders of magnitude. Separately, ArchUnit inspects dependencies and calls but cannot see arithmetic operators or primitive arithmetic.

**Decisions.**
- `testkit.CommandSigner.FAST` produces `signature = SHA-512(pubKey || unsignedBytes)`, and `FAST_VERIFIER` checks exactly that. It is **not** a signature scheme, since anyone can compute it, and it exists only in `testkit`. Property and differential runs inject it through the normal `SignatureVerifier` parameter, so authentication logic is still exercised: wrong key, tampered bytes, replays, and chain id. Real Ed25519 is covered by `crypto` tests (including RFC 8032 vectors) and by `ExecutionCoreTest.realEd25519EndToEnd`.
- `ArchRulesTest` combines ArchUnit rules (clock, randomness, threads/concurrency, I/O/logging, hash-ordered iteration, floating-point fields) with a source scan. The scan strips comments and literals, then bans every raw `*` (defect 9) and every `double`/`float`/`BigDecimal` in the deterministic sources (`engine` except `Sequencer*`, and `SimpleMerkle`). `OrderBook.index` is declared as `HashMap` so that iteration over it is visible to ArchUnit.

**Consequences.** Nightly runs stay in minutes rather than hours. Any multiplication in engine code must use `Math.multiplyExact`, even where it cannot overflow.
