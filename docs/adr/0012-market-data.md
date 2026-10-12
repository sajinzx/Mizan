# ADR 0012: Market data as full-state packets over unicast UDP

**Context.** §6.6 describes `DEPTH` packets, gap detection by sequence number, and a TCP snapshot. With *incremental* packets, every lost delta corrupts the view until a snapshot arrives, and any mistake in buffering or ordering leaves it wrong. Multicast on Windows/localhost is unreliable for tests.

**Decision.**
- A `DEPTH` packet carries the **full top-35 state** of one instrument (`15 + 16·70 = 1,135` bytes ≤ 1,200). A subscriber replaces that instrument's view if the packet's `seq` is newer than the last one it applied, so loss, duplication and reordering cannot corrupt it.
- Every packet (`DEPTH`, `TRADE`, `REVOKE`) increments a global `seq`. `HEARTBEAT` (every 500 ms in the node) carries the latest `seq`. A jump in `seq`, or a heartbeat ahead of everything received, triggers one TCP `SNAPSHOT` (all levels, all instruments). The snapshot replaces instruments whose applied `seq` is older than its own. A lost final packet is repaired by the next heartbeat, which gives the "converges to the exact depth" property.
- An extra packet type `TRADE` (4) feeds the UI's trade tape. Lost trade and revoke prints are not recovered (informational only; a client's own fills arrive over TCP).
- Subscribers send `SUBSCRIBE` datagrams every second, the publisher fans out by unicast, and silent subscribers expire after 10 s.
- The engine-side listener only offers to a bounded SPSC queue. On overflow it counts (`overflowCount`) instead of blocking, and the depth view is then stale. The queue (64 Ki events) is sized so this does not happen in normal operation, and the counter is exported.

**Consequences.** UDP bandwidth grows with depth (full state rather than deltas), which is acceptable at 35 levels. Correctness rests on per-instrument sequence comparison, which the 10%-loss proxy test exercises.
