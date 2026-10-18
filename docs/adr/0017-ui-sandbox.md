# ADR 0017: The dashboard embeds a sandbox network (`ui` → `node`)

**Context.** The M5/M9 dashboard only connected to an already running node and showed one ladder, a trade list and a chain panel. For exploring Mizan, the owner wants one application that starts everything and shows the whole system working. That means a market-maker-style terminal with a lot of live data, every feature visible, and controls to break things and watch the guarantees hold.

**Decision.**
- `mizan-ui` may depend on `mizan-node` (new allowed edge; `checkModuleGraph` updated). The default launch starts a `Sandbox` in-process:
  - 4 rotating-proposer validators over real localhost TCP, each with its own block log, snapshots and consensus WAL, on state root v2;
  - bots (`MarketBots`) that are real signed clients, plus a funded account for the user;
  - a `ShadowReplica` that re-executes each committed height once, whichever validator reports it first.
- The dashboard reads one immutable `SandboxView.Snapshot` about ten times a second, built off the JavaFX thread.
- Market views (book, candles, tape, your balances and open orders) come from the shadow replica's committed state. The determinism check compares every running validator's reported state root with the shadow's root at the same height.
- Fault controls go through public, clearly labelled hooks:
  - `ValidatorNode.setBlockedPeers` (partition and isolation);
  - stopping and restarting a validator from its data directory;
  - `verify-chain`.
- `--connect` keeps the old dashboard for an external node.

**Consequences.**
- The UI JVM runs the whole network, about 1 GB of heap at the default bot rate.
- "Stop" is a graceful close, not a kill -9: hard crashes stay covered by `recoveryTest` and `chaosTest`.
- Bots are sandbox scaffolding, not part of the exchange.
