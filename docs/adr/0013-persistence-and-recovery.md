# ADR 0013: Persistence, recovery and rollback details

**Context.** M6 implements §6.7 and the commit path. Several details were unspecified, and a few needed adjusting.

**Decisions.**
- **Recovery lives in `chain`**, not `store`. It decodes blocks and certificates, and `store` may not depend on `chain` (§4.2). `store` stays byte-level: `BlockStore`, `SnapshotStore`, `FaultPoints`.
- **The commit certificate encodes its round** (`round:4` before `n:4`). The signed vote bytes include the round, so a cert without it cannot be verified.
- **Genesis anchor.** Block 1's `prevHash` is `SHA256("MIZAN-GENESIS-V1" || chainId || genesisStateRoot)`. The data directory records the anchor, and a node refuses to start on a directory created for another genesis.
- **Validate before persisting.** The consensus thread replays each block on a *copy* of the committed core and checks the root. Only then does it append and fsync, and the copy becomes the committed core. A block in the log has therefore always been validated.
- **Torn tail versus corruption.** A crash can only tear the last record: an incomplete record, or a final record whose CRC fails, is truncated. A CRC failure followed by more data refuses to start. *Limitation:* a corrupted length field that points past EOF looks exactly like a torn tail. As a guard, recovery also refuses when the newest snapshot is beyond the log, because committed blocks are then missing.
- **Snapshots** are temp file + fsync + atomic rename. The newest 2 are kept, and a corrupt snapshot is skipped in favour of an older one. On Windows the directory fsync is skipped, since NTFS journals the rename.
- **Rollback.** `Rollback(freshCopy, extra)` makes the *sequencer* replay every in-flight draft, then `pending`, then `extra`, so nothing accepted provisionally is dropped. Drafts carry an `epoch` (number of rollbacks), and the chain driver ignores drafts from an older epoch.
- **Result-cache rebuild** uses the blocks replayed after the newest snapshot (up to 64). Older outcomes answer `UNKNOWN` on `QUERY_STATUS`, and a retry gets a stale-nonce reject. This is safe, just less informative.
- **Fault points** (`-Dmizan.fault=<name>:<n>`) halt the JVM with `Runtime.halt` at: before append, mid-append (half a record written and fsynced), after append before a snapshot, mid-snapshot (half a temp file), and mid-block (during validation replay).

**Consequences.** The crash test kills a child JVM at each point and at random moments. After every kill it checks recovery against an independent full replay and against every `FINAL` a client received.
