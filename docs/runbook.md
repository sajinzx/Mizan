# Runbook

This runbook covers operating Mizan nodes on one machine or a trusted network. Everything uses the `mizan` launcher:

```sh
./gradlew :mizan-node:installDist
alias mizan=mizan-node/build/install/mizan/bin/mizan     # Windows: mizan-node\build\install\mizan\bin\mizan.bat
mizan --help
```

## Start

| Goal | Command |
|---|---|
| Throwaway single node (in memory, dev genesis) | `mizan node --dev --port 9000 --md-port 9001` |
| Single validator with persistence | `mizan genesis --out genesis.json` then `mizan node --genesis genesis.json --data data --key dev:0` |
| Local 4-validator cluster (rotating proposer) | `mizan cluster --nodes 4 --data cluster-data --port 9000 --metrics-port 9100` |
| Same, M7 fixed proposer | add `--fixed-proposer` |
| Load | `mizan load --port 9000 --traders 100 --orders 1000` |

For a rotating cluster, validator `i` serves clients on `port + i` and metrics on `metrics-port + i`. Validator 0 also serves UDP market data on `--md-port`. Each validator has its own `cluster-data/v<i>` directory.

**Keys.** `mizan keygen --out v0.key` writes a base64 Ed25519 seed and prints the public key; it never overwrites an existing file. The dev keys (`dev:<i>`, `dev-admin`, `dev-account:<n>`) are public, so use them only locally. Restrict key-file permissions to the node's user.

**Funding accounts.** Run `mizan faucet --port 9000 --admin-key dev-admin --account 1000 --account-key dev-account:1000 --asset 1 --amount 1000000`. `--account-key` registers the account first. Exit code 0 means the deposit is `FINAL`.

## Data directory

| File | What it is | Safe to delete? |
|---|---|---|
| `genesis.anchor` | Hash of the genesis the directory belongs to; start refuses a different genesis | No |
| `blocks.log` | The chain: `[len][crc32c][block‖cert]` records, fsynced. **This is the WAL** (ADR 0002) | **Never** |
| `snapshots/snapshot-<height>.bin` | State at a height, CRC-protected; the newest 2 are kept | Yes (recovery replays from genesis instead) |
| `consensus.wal` | Last signed votes and locks (BFT only) | **Never while the chain is live**: losing it can make this validator equivocate after restart |
| `evidence.log` | Equivocation evidence seen by this validator | Yes (but it is evidence) |

## Health checks

- `curl -s 127.0.0.1:9100/metrics` (needs `--metrics-port`). Watch these:
  - `mizan_chain_height`: rising. Empty blocks keep it moving in BFT mode.
  - `mizan_consensus_round_bumps_total`: rising fast means a proposer is down or the network is bad.
  - `mizan_gateway_busy_total`: rising means clients are being throttled (rate limits) or the queues are full.
  - `mizan_sequencer_inbound_depth`, `mizan_pool_depth`, `mizan_gateway_awaiting`: sustained growth means a backlog.
  - `mizan_gateway_ingest_to_final_us{quantile="0.99"}`: client-visible finality latency.
  - `mizan_consensus_equivocations_total`: anything above 0 means a validator double-signed (see `evidence.log`).
- Logs go to stderr as `event key="value"` lines. Use `-Dmizan.log.format=JSON` for one JSON object per line, and `-Dmizan.log.level=DEBUG` to see every block. Pass JVM flags through `JAVA_OPTS`.

## Procedures

### Restart a node or validator
Stop it (Ctrl-C or SIGTERM; the shutdown hook lets an in-flight fsync finish), then start it with the same command. On start it logs `recovered`/`node_start` with the height. A torn tail from a crash is truncated automatically (`tornBytes`). A restarted validator catches up by sync (`synced` events) and resumes voting.

### Verify a data directory
```sh
mizan verify-chain --data cluster-data/v2 --genesis cluster-data/genesis.json
```
This is read-only. It replays from genesis, checks every link, commit certificate and state root, and checks every snapshot against the replayed chain. `OK` prints the height, head hash and state root. Compare those across validators: at equal heights they must be identical. Exit code 1 prints the first failing height or snapshot.

### Startup refused: "corrupt at offset … refusing to start"
The block log is damaged *before* its last record. That cannot result from a crash (ADR 0013), so it points to disk or manual damage.
1. Do not delete or edit `blocks.log`. Copy the whole data directory aside.
2. For a validator in a cluster: stop it, move the data directory away, and start it with an empty one (same genesis and key, **keeping `consensus.wal`**). It re-syncs all blocks from its peers. Then run `verify-chain` on the new directory.
3. For a single validator there is no other copy, so the blocks after the corruption are lost. Restore from a backup and run `verify-chain`.

### Startup refused: "belongs to a different genesis"
The `--genesis` file differs from the one the directory was created with. Use the original genesis file. Never edit `genesis.anchor`.

### Startup refused: snapshot or replayed-root mismatch
Run `verify-chain` to locate the problem. If only a snapshot is bad, move `snapshots/` aside and restart; recovery replays from genesis. A replayed-root mismatch in the block log means the code no longer reproduces history (a determinism bug). Do not run that build. Report it with the height.

### A validator is down
With n = 4, one validator down is tolerated (f = 1): heights continue with `round_bump`s whenever the down validator is proposer. With two down, the chain stops (no quorum) and resumes when either returns. Clients connected to a down validator should reconnect to another validator and retry with the **same nonce**; that is idempotent (ADR 0003).

### Equivocation evidence
Collect `evidence.log` from all validators. Each line shows the validator, height, round, kind, and both conflicting hashes. There is no slashing. Remove the validator by creating a new genesis (no validator-set changes exist).

### Backups
Copy `blocks.log`, `genesis.anchor` and `snapshots/` while the node runs. Appends are record-atomic, so a copy has at most a torn tail. Verify the copy with `verify-chain`. Restoring a validator from a backup is safe only if you also keep its current `consensus.wal`. If in doubt, start from an empty directory and let it sync instead.
