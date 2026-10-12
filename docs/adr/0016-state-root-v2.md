# ADR 0016: State root v2, a persistent sparse Merkle tree with dirty-key batching

**Context.** State root v1 (`SimpleMerkle` over all leaves) costs O(state) per block. M10-T2 asks for a depth-64 `SparseMerkleTree` with default-hash empties and dirty-key batching, behind `stateRootVersion = 2`. Two constraints shape the design:
- Validators copy the committed core for every block they validate (`ExecutionCore.copy()`). A tree that stores all nodes of the depth-64 tree would have to be deep-copied each time, which costs more than recomputing v1.
- A naive depth-64 tree stores about 64 nodes per key.

**Decision.**
- **Hash definition** (normative): `leaf = SHA256(0x00‖key‖value)` (same leaf bytes as v1), `node = SHA256(0x01‖left‖right)`, `EMPTY[0] = 0³²`, `EMPTY[h+1] = node(EMPTY[h], EMPTY[h])`. The child at height `h` is bit `h−1` of the key, so the root splits on the most significant bit. `stateRoot = SHA256(0x03‖ledgerRoot‖metaRoot‖bookRoot‖nextTradeSeq‖nextOrderId)`. The tag is `0x03`, where v1 uses `0x02`, so roots of the two versions can never be equal for the same state.
- **Storage is not the definition.** A subtree holding a single key is stored as one node that caches the hash it has in the full tree. Empty subtrees are `null`, and only subtrees with two or more keys are branches. Memory is O(keys).
- **Persistent.** Updates return a new tree that shares untouched nodes, so `ExecutionCore.copy()` copies three references and the pending dirty-key lists.
- **Dirty keys.** `Ledger` (`move`, `register`, `consumeNonce`, the snapshot-restore methods) and `OrderBook` (`rest`, `cancel`, maker fills and self-trade cancels) report changed keys. `stateRoot()` recomputes those leaves from current state and applies each tree's keys in one sorted batch, so every affected node is rebuilt once.
- **Oracle.** `ExecutionCore.stateRootFromScratch()` rebuilds the trees from all state. `SmtStateRootTest` asserts incremental == from scratch after random command streams, at varying batch sizes, across copies and snapshot round trips. A missed dirty mark anywhere fails it. `SparseMerkleTreeTest` checks the tree against a by-definition recursive reference.
- Genesis chooses the version. v1 remains the default for existing genesis files, and a data directory cannot switch versions, because the genesis anchor hashes the whole genesis.

**Consequences.** Block cost grows with the number of changed keys rather than with total state (numbers in `docs/bench/m10.md`). No more than one version of state-root code runs on one chain. Light clients could prove inclusion and exclusion against v2 roots, but no proof API is exposed yet.
