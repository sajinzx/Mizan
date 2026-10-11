# ADR 0002: The block log is the write-ahead log

**Context.** Commands execute immediately on the speculative core, but a crash must never lose a result that a client was told is final.

**Decision.** There is no separate command WAL. A command becomes durable only when the block carrying it has been committed (2f+1 precommits) **and fsynced** to the append-only block log (`[len][crc32c][Committed]`, `FileChannel.force(true)`). Only then does the gateway send `FINAL`. `ACCEPTED_PROVISIONAL` results can be revoked. Recovery replays the log from the latest verified snapshot, truncating a torn tail and refusing mid-log corruption.

**Consequences.** Uncommitted commands are lost in a crash, and clients retry them with the **same nonce** (ADR 0003). The ack contract is: `BUSY` (nothing executed), `REJECTED` (durable once its block is FINAL), `ACCEPTED_PROVISIONAL` (does not survive a crash), and `FINAL` (survives). Commit latency includes an fsync per block. Group commit is an optional later optimization (M10).
