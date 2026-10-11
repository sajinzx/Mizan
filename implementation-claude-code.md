# Mizan — Implementation Spec for Claude Code (v3, merged)

> **Name:** `mizan` (originally drafted as `chainbook`; renamed at project start — see ADR 0004). Package root is `com.sajin.mizan`.
> **Status:** design + reference snippets. Nothing here has been compiled or benchmarked. Every performance figure is a *target to measure*, never a claim.
> **This file merges** the original design (Doc B) with the evidence-first roadmap (Doc A): Doc B's design, Doc A's gates, nine known defects already fixed in the spec, and twelve conflicts resolved (§12).
> **Deviations** from this text made during implementation are recorded as ADRs in `docs/adr/` and take precedence.

---

## 0. How Claude Code should use this file

### 0.1 Session start checklist (do this every session)

1. Read this file top to bottom once; afterwards re-read only §1–§6 and the current milestone.
2. Open `PROGRESS.md` (create from §13 if missing). Resume at the first unchecked task.
3. Run `./gradlew build` first. If it is red, fix that before anything else.
4. Work **one task card at a time** (`Mx-Ty`). Never start the next milestone until the current gate (§8, "Gate") passes.

### 0.2 Working agreements

| Rule | Detail |
|---|---|
| Plan briefly | Before coding a task, write ≤ 5 lines: files you will touch, tests you will add. |
| Tests ship with code | Every task commits implementation **and** its tests together. A task is not done until its `Verify` command is green. |
| Commit per task | Message format: `M2-T4: ledger reserve/settle/release (+ props)`. Small commits; no mixed tasks. |
| Update `PROGRESS.md` | Tick the box, add one line of notes (decisions, surprises, numbers). |
| Ambiguity | Pick the simplest option that satisfies the **Hard rules** (§2), then record it as an ADR in `docs/adr/NNNN-title.md` (3–10 lines: context, decision, consequences). Do not stop to ask unless truly blocked. |
| Dependencies | Only those in §3. Anything else needs an ADR first. |
| Crypto | JDK only. Never hand-write a hash, signature, or RNG. |
| Regression tests | Each defect in §7 has a named regression test. The test must exist before the fix is merged and must fail without it. |
| No silent scope cuts | If you cut something (see §11 cut order), note it in `PROGRESS.md` and the README limitations. |
| Honest numbers | Benchmark claims must link to raw output in `docs/bench/`. Do not fabricate or estimate numbers. |

### 0.3 Prompt to start a task (copy/paste)

```
Read implementation-claude-code.md and PROGRESS.md. Implement task <Mx-Ty> exactly as specified,
including its tests. Run its Verify command. Update PROGRESS.md and commit as "<Mx-Ty>: ...".
Stop after this task and summarize what changed.
```

### 0.4 Java/JDK pitfalls to avoid (common mistakes)

- `Ed25519`: `Signature.getInstance("Ed25519")`; JDK public keys encode as **X.509 (44 bytes)**; rebuild with `KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes))`.
- `ByteBuffer` is big-endian by default; keep it that way everywhere. Never use `toString()`, `Object.hashCode()`, or Java serialization in anything hashed or signed.
- Records with `byte[]` components use identity `equals`. In tests compare via helper `Bytes.eq(...)` or `Arrays.equals`; don't rely on record equality.
- Virtual threads: avoid `synchronized` around blocking I/O (pinning on Java 21); use `ReentrantLock`.
- TCP is a stream: always `readFully`; test framing at **every byte boundary**.
- `HashMap` is fine for lookups; **never iterate it in anything that affects output or hashes** inside `engine`.
- `Math.multiplyExact/addExact` for all money math (see §2).

---

## 1. Project in ten lines

1. A deterministic multi-instrument limit-order-book engine with an escrowed multi-asset ledger.
2. One **sequencer** thread owns all speculative trading state; all other threads talk to it through bounded queues.
3. Commands are **Ed25519-signed envelopes** with per-account nonces and a `chainId`.
4. The engine returns provisional fills immediately (`ACCEPTED_PROVISIONAL`).
5. Fills become **`FINAL`** only when a block carrying them is committed and **fsynced**.
6. **Core tier (M1–M6):** engine + sequencer + TCP gateway + UDP market data + block log + crash recovery. This alone is a complete, defensible project.
7. **Extended tier (M7–M10):** Tendermint-lite BFT over a simulated and real network, rotating proposers, block sync, gossip, hardening, optimization.
8. State root = Merkle over ledger + account meta + resting book + counters. Every node must compute the same root.
9. Testing is differential (vs a naive `ReferenceEngine`), property-based, crash-injected, and fault-sweep seeded.
10. Simulated assets only. No real money, no custody, no hand-written crypto.

---

## 2. Hard rules (violations are bugs)

**Determinism — applies to `ExecutionCore`, `Ledger`, `OrderBook`, `PriceLevel`, `StateCodec`, `Merkle*`:**

- No clock, randomness, I/O, threads, or logging.
- No iteration over hash-ordered collections where order can affect output. Use `TreeMap`/`TreeSet`/sorted lists.
- `long` ticks and lots only. No `float`/`double`/`BigDecimal`.
- Money math uses `Math.multiplyExact` / `addExact` / `subtractExact` **everywhere**. Overflow ⇒ deterministic reject (or `InvariantViolationError` if it could only happen through a bug).
- Canonical, versioned byte encodings only.
- A rejected command changes **nothing except its burned nonce** (see §6.3).
- `InvariantViolationError` is never caught by the engine. The node halts.

**Ownership:**

- The speculative `ExecutionCore` is touched only by the `sequencer` thread. The committed `ExecutionCore` is touched only by the `consensus` thread. Nothing else reads engine state directly; others receive events through queues.

**Scope:** educational, simulated assets only; no real-money custody; no compliance claims.

ArchUnit/grep tests (M2) enforce: no `System.currentTimeMillis/nanoTime`, `Random`, `Thread`, `java.io`/`java.nio.file`, `HashMap`/`HashSet` iteration, or raw `*` on money fields inside the packages above.

---

## 3. Tech stack and build conventions

| Concern | Choice |
|---|---|
| Language/runtime | Java 21 (LTS) toolchain |
| Build | Gradle 8.x, Kotlin DSL, multi-module, version catalog `gradle/libs.versions.toml` (pin latest stable at project start) — *see ADR 0005: Gradle 9.x* |
| Low-latency | Agrona (`ManyToOneConcurrentArrayQueue`, `OneToOneConcurrentArrayQueue`, `Long2ObjectHashMap`, `BackoffIdleStrategy`) |
| Crypto | JDK `Signature("Ed25519")`, `KeyFactory`, `MessageDigest("SHA-256")` behind `Signer` / `SignatureVerifier` |
| Persistence | Own append-only files (`FileChannel`, CRC32C, fsync) |
| Latency stats | HdrHistogram |
| Config/CLI | Jackson (only in `node`, `ui`), picocli |
| Logging | SLF4J + Logback outside `engine`. The engine never logs; it emits events. |
| UI | JavaFX 21 via `org.openjfx.javafxplugin` |
| Tests | JUnit 5, jqwik, AssertJ, ArchUnit; Mockito only if unavoidable; jcstress optional |
| Quality | Spotless (google-java-format), Error Prone, JaCoCo, `-Xlint:all` (warnings fail CI) |
| Bench | JMH (`me.champeau.jmh`), async-profiler/JFR |
| CI | GitHub Actions: `ci.yml` (build) + `nightly.yml` (long runs) |

### 3.1 Gradle tasks and test tags (define in M1-T1)

| Command | Meaning |
|---|---|
| `./gradlew build` | Compile, Spotless check, unit + short property tests (excludes tags below), JaCoCo |
| `./gradlew nightly` | Property tests at `-Pjqwik.tries=100000`; includes tag `slow` |
| `./gradlew recoveryTest` | Tag `recovery` (child-JVM kill/restart) — default 20 iterations, `-Piter=200` for the gate |
| `./gradlew simSweep -Pseeds=1000` | Tag `sim` (seeded consensus fault sweeps) |
| `./gradlew :mizan-bench:jmh` | JMH benchmarks |
| `./gradlew checkModuleGraph` | Fails if any project dependency edge is not in §4.2 (hooked into `check`) |

Default `test` excludes tags `slow`, `recovery`, `sim`. Every seeded test prints `SEED=<n>` and a one-line replay command on failure.

---

## 4. Repository layout

### 4.1 Tree (create files as the tasks require)

```
mizan/
├─ PROGRESS.md
├─ README.md                      (stub in M1, real in M10)
├─ settings.gradle.kts
├─ build.gradle.kts               (conventions, tasks, checkModuleGraph)
├─ gradle/libs.versions.toml
├─ .github/workflows/{ci.yml,nightly.yml}
├─ docs/
│  ├─ assumptions.md  protocol.md (M7 wk1)  threat-model.md  runbook.md
│  ├─ adr/0001-single-writer.md 0002-block-log-is-wal.md 0003-nonce-idempotency.md …
│  └─ bench/…                      (raw JMH/HdrHistogram output + methodology)
├─ mizan-core/     …core    Side, OrderType, TimeInForce, commands, SignedCommand, Trade, ExecResult,
│                           Limits, exceptions, listeners, CommandSink, CommandCodec, GenesisConfig, Instrument
├─ mizan-crypto/   …crypto  Hashing, SimpleMerkle, SparseMerkleTree (M10), Signer, SignatureVerifier, CachingVerifier
├─ mizan-engine/   …engine  Order*, PriceLevel, OrderBook, Ledger, ExecutionCore, StateCodec, Sequencer, SimpleStateRoot
├─ mizan-store/    …store   BlockStore, SnapshotStore, ConsensusWal, Recovery
├─ mizan-chain/    …chain   BlockHeader, Block, CommitCert, ConsensusEngine, AbstractConsensus,
│                           SingleNodeConsensus, RoundBft (M7), Network/Clock/Scheduler SPI
├─ mizan-net/      …net     FrameCodec, TcpGateway, ResultCache, TcpNetwork, MarketDataPublisher, gossip, sync
├─ mizan-node/     …node    wiring, CLI, metrics, config
├─ mizan-testkit/  …testkit ReferenceEngine, generators, Fixtures, SignedCommands, StateDump, Differential
├─ mizan-sim/      …sim     SimWorld, SimNetwork, LoadGenerator, MizanClient, Mempool, AuditTap
├─ mizan-ui/       …ui      JavaFX dashboard
└─ mizan-bench/    …bench   JMH
```

### 4.2 Allowed project dependencies (enforced by `checkModuleGraph`)

```
core, crypto            → (none)
engine                  → core, crypto
store                   → core, crypto, engine
chain                   → core, crypto, engine, store
net                     → core, chain            (talks to the engine only via core.CommandSink / listeners)
node                    → core, crypto, engine, store, chain, net, sim
testkit                 → core, crypto, engine, chain      (test/demo only)
sim                     → core, crypto, engine, chain, net, testkit
ui                      → core, net
bench                   → core, crypto, engine, chain, testkit
```

---

## 5. Contracts (types, formats, limits)

### 5.1 Limits (`core.Limits`)

```java
public static final long MAX_PRICE = 1L << 31;      // ticks
public static final long MAX_QTY = 1L << 31;        // lots
public static final long MAX_DEPOSIT = 1L << 50;
public static final long MAX_ACCOUNT = (1L << 47) - 1;
public static final int  MAX_ASSET = 0xFFFF;
public static final int  MAX_ENVELOPE_BYTES = 256;
// price*qty <= 2^62 so a single multiplyExact on one order cannot overflow.
// Balance accumulation uses addExact; overflow => LimitExceededException (deterministic reject).
```

### 5.2 Exceptions

```text
Unchecked, deterministic, thrown BEFORE state mutation:
  OrderRejectedException (abstract)
    InvalidOrderException | InsufficientBalanceException | LimitExceededException
    InvalidSignatureException | StaleNonceException
Unchecked, fatal: InvariantViolationError extends Error     (node halts)
Checked: ProtocolException extends IOException | CryptoException extends Exception
         ChainUnavailableException extends Exception | PersistenceException extends IOException
```

### 5.3 Command envelope (big-endian, version 1)

```
0   1  version = 1
1   1  tag: 1=REGISTER 2=DEPOSIT 3=PLACE 4=CANCEL
2   4  chainId
6   8  accountId (signer)
14  8  nonce          (must be > account.lastNonce)
22  …  body
…   64 Ed25519 signature over bytes [0, end of body)

REGISTER: [pubKeyLen:1 = 44][X.509 Ed25519 pubkey:44]
DEPOSIT : [target:8][asset:2][amount:8]                       (admin account 1 only)
PLACE   : [clientOrderId:8][instrument:2][side:1][type:1][tif:1][price:8][qty:8]
CANCEL  : [orderId:8][instrument:2]
```

Decode rules: reject unknown version/tag, wrong pubkey length, ordinals out of range, trailing or missing bytes, total length > `MAX_ENVELOPE_BYTES` — **only** `ProtocolException`, never any other exception.

Genesis creates **account 0** (fee account, no key, can never sign) and **account 1** (admin/faucet with the genesis admin key). Only account 1 may `DEPOSIT`. **Order ids are assigned by the engine** (`nextOrderId`, which doubles as `arrivalSeq`). Replace-order = **cancel-and-new** (loses priority; document this, no `REPLACE` command).

### 5.4 Core types

```java
public enum Side { BUY, SELL }
public enum OrderType { LIMIT, MARKET }
public enum TimeInForce { GTC, IOC, FOK, POST_ONLY }

public sealed interface OrderCommand permits Register, Deposit, Place, Cancel {}
public record Register(byte[] pubKey) implements OrderCommand {}
public record Deposit(long target, int asset, long amount) implements OrderCommand {}
public record Place(long clientOrderId, int instrument, Side side, OrderType type,
                    TimeInForce tif, long price, long qty) implements OrderCommand {}
public record Cancel(long orderId, int instrument) implements OrderCommand {}

public record SignedCommand(int chainId, long accountId, long nonce,
                            OrderCommand body, byte[] signature, byte[] raw) {}

public record Trade(long seq, int instrument, long makerOrderId, long takerOrderId,
                    long buyAccount, long sellAccount, Side takerSide,
                    long price, long qty, long buyEscrowPrice /* 0 => buyer not pre-escrowed (market buy) */) {}

public record ExecResult(Status status, long orderId, String reason) {
    public enum Status { AUTH_FAILED, REJECTED, ACCEPTED }
}

public interface TradeListener { void onTrade(Trade t); default void onReject(long account, long ref, String reason) {} }
public interface EngineListener extends TradeListener {
    default void onAccepted(long account, long clientOrderId, long orderId) {}
    default void onCancelled(long orderId, String reason) {}                    // user cancel or "STP"
    default void onBookDelta(int instrument, Side side, long price, long aggQty) {}
    default void onRevoke(long fromTradeSeq) {}
}
public interface CommandSink { boolean submit(SignedCommand c); }               // false => BUSY
public interface ResultListener { void onResult(SignedCommand c, ExecResult r); }          // provisional
public interface FinalityListener { void onFinal(long height, List<SignedCommand> cmds, List<ExecResult> results); }
```

`Order` hierarchy: `abstract sealed class Order permits LimitOrder, MarketOrder` with `id, accountId, instrument, side, tif, remaining` and abstract `limitPrice()`, `rests()`, `escrowsUpfront()`; `crosses(restingPrice)` concrete.
- `LimitOrder`: `rests()` = `tif ∈ {GTC, POST_ONLY}`; `escrowsUpfront()` = `true`.
- `MarketOrder` (price = collar): `rests()` = `false`; `escrowsUpfront()` = `side == SELL`. **Validation: `MARKET` ⇒ `tif == IOC`**, else `InvalidOrderException`.

### 5.5 Client protocol (TCP, length-prefixed frames)

Frame = `[len:4][type:1][payload]`, `len = 1 + payload.length`, `MAX_FRAME = 1<<20`, `len < 1 || len > MAX_FRAME` ⇒ `ProtocolException`.

| Type | Dir | Payload |
|---|---|---|
| `0x01 ORDER` | C→S | `SignedCommand.raw` |
| `0x02 QUERY_STATUS` | C→S | `[account:8][nonce:8]` |
| `0x10 BUSY` | S→C | `[account:8][nonce:8]` (retry with **same** nonce) |
| `0x11 ACCEPTED_PROVISIONAL` | S→C | `[account:8][nonce:8][orderId:8]` |
| `0x12 REJECTED` | S→C | `[account:8][nonce:8][reasonCode:2][msgLen:2][msg]` |
| `0x13 FINAL` | S→C | `[account:8][nonce:8][height:8][status:1][orderId:8]` |
| `0x14 STATUS` | S→C | `[account:8][nonce:8][state:1 (0 unknown,1 provisional,2 rejected,3 final)][height:8][orderId:8]` |
| `0x20 TRADE` | S→C | trade fields for the connection's account |
| `0x21 REVOKE` | S→C | `[fromTradeSeq:8]` |
| `0x1F ERR` | S→C | `[code:2]` then close |

**Ack contract:**

| State | Meaning | Survives crash? |
|---|---|---|
| `BUSY` | Queue full, nothing executed | n/a |
| `REJECTED` | Deterministic reject with reason | Yes, once its block is FINAL |
| `ACCEPTED_PROVISIONAL` | Executed on speculative core; fills may be **revoked** | No |
| `FINAL` | Block committed (2f+1 precommits) **and fsynced** | Yes |

**Durability statement (ADR 0002):** the block log is the WAL. Only `FINAL` commands survive a crash; uncommitted commands are lost and clients retry with the same nonce.

**Idempotency (ADR 0003):** nonce is the idempotency key. In addition, the gateway keeps a bounded `ResultCache` keyed by `(account, nonce)` (LRU, ≤ 1024 per account, ≤ 1M total; node-local, **not consensus state**; rebuilt on startup by replaying the last K committed blocks). A duplicate `ORDER` whose key is cached returns the cached answer without resubmitting. `QUERY_STATUS` lets a client learn the outcome after a missed ack. A stale nonce that is not cached ⇒ engine `StaleNonceException` (safe, no double execution).

### 5.6 State root

```
stateRoot = SHA256( 0x02 || ledgerRoot || metaRoot || bookRoot || tradeSeq:8 || nextOrderId:8 )

ledger leaf : key=(accountId<<16 | assetId)  value = free:8 | held:8        (omit when both zero)
meta   leaf : key=accountId                  value = lastNonce:8 | SHA256(pubKey):32
book   leaf : key=orderId                    value = instrument:2 | side:1 | account:8 | price:8 | remaining:8
```

- **M2–M9:** `SimpleMerkle.root(leaves sorted by key)`; leaf bytes `0x00 || key:8 || value`, node bytes `0x01 || left || right`; odd node is paired with itself; empty tree = 32 zero bytes. `stateRootVersion = 1`.
- **M10:** `SparseMerkleTree` (depth 64, default-hash empties, dirty-key batching). Bump `stateRootVersion = 2` in genesis; the two versions must not be silently interchangeable.
- FIFO within a price level is fixed by `orderId` (== arrival order), so the root pins queue order.

### 5.7 Block, vote, consensus-WAL formats (used from M6/M7)

```
BlockHeader = [version:1][chainId:4][height:8][round:4][prevHash:32][txRoot:32][stateRoot:32][proposer:4][timestampMillis:8]
blockHash   = SHA256(header bytes)
txRoot      = SimpleMerkle.root over each SignedCommand.raw (index order)
Block       = header || count:4 || (len:4 || raw)*
CommitCert  = n:4 || (validatorIndex:4 || sig:64)*         // sigs over Precommit sign-bytes
Vote sign-bytes = "CBVOTE1" || chainId:4 || type:1 (1=prevote,2=precommit) || height:8 || round:4 || blockHash or 32 zero bytes (nil)
Committed   = Block || CommitCert
```

Timestamps are metadata only; the engine never reads them.

### 5.8 Genesis (JSON, loaded in `node`; immutable types in `core`)

```json
{ "chainId": 4242, "stateRootVersion": 1, "adminPubKey": "<b64 X.509>",
  "assets": [{"id":1,"name":"BASE"},{"id":2,"name":"QUOTE"}],
  "instruments": [{"id":1,"base":1,"quote":2,"tick":1,"lot":1,"makerBps":0,"takerBps":10,
                   "maxPrice":2147483648,"maxQty":2147483648}],
  "validators": [{"index":0,"pubKey":"<b64>","host":"127.0.0.1","port":7000}],
  "block": {"maxCommands":500,"maxIntervalMs":50,"maxInflight":1},
  "consensus": {"proposeTimeoutMs":1000,"timeoutDeltaMs":500} }
```

---

## 6. Algorithms (precise rules)

### 6.1 Ledger (multi-asset, escrow, fees, nonces)

State: `TreeMap<Long,Bal> bal` (key `acct<<16|asset`), `TreeMap<Long,Meta> meta` (`pubKey`, `lastNonce`). Reads use a `peek` that never creates entries.

| Operation | Rule |
|---|---|
| `register(acct, pk)` | `0 < acct ≤ MAX_ACCOUNT`, not already registered, else `InvalidOrderException` |
| `consumeNonce(acct, n)` | sets `lastNonce = n`; the **only** mutation allowed before business validation |
| `deposit(acct, asset, amt)` | `0 < amt ≤ MAX_DEPOSIT`, account registered; `addExact` overflow ⇒ `LimitExceededException` |
| `reserve(order)` | if `!escrowsUpfront()` return. BUY: `need = multiplyExact(limitPrice, remaining)` in quote; SELL: `need = remaining` in base. `free < need` ⇒ `InsufficientBalanceException` **before any mutation**; else `free -= need; held += need` |
| `settle(trade)` | see below |
| `release(order)` | if `!escrowsUpfront()` return. BUY: `a = multiplyExact(limitPrice, remaining)` quote `held -= a; free += a`; SELL: base `held -= remaining; free += remaining` |
| invariant | any negative `free`/`held` after a move ⇒ `InvariantViolationError` |

`settle(trade)`; fees are charged **in the asset each side receives** (no escrow headroom needed), floor rounding, `bps(x, b) = multiplyExact(x, b) / 10_000`:

```java
long cost = Math.multiplyExact(t.price(), t.qty());
boolean buyerIsTaker = t.takerSide() == Side.BUY;
long baseFee  = bps(t.qty(), buyerIsTaker ? in.takerBps() : in.makerBps());   // buyer receives base
long quoteFee = bps(cost,    buyerIsTaker ? in.makerBps() : in.takerBps());   // seller receives quote
if (t.buyEscrowPrice() == 0) move(buyAcct, quote, -cost, 0);                  // market buy: pay from free
else { long esc = Math.multiplyExact(t.buyEscrowPrice(), t.qty());
       move(buyAcct, quote, esc - cost, -esc); }                              // refund price improvement
move(sellAcct, base, 0, -t.qty());
move(buyAcct,  base,  t.qty() - baseFee, 0);
move(sellAcct, quote, cost - quoteFee, 0);
move(FEE_ACCOUNT, base, baseFee, 0);  move(FEE_ACCOUNT, quote, quoteFee, 0);
```

Conservation: for every asset, `sum(free + held)` over **all accounts including account 0** equals the sum of successful deposits.

### 6.2 Matching (`OrderBook.match`)

`OrderBook`: `TreeMap<Long,PriceLevel> bids (descending), asks (ascending)`, `Map<Long,Order> index`. `PriceLevel` hides its storage (`ArrayDeque` + running `total` in M2; intrusive list in M10) — **keep that API from day one**.

```java
long match(Order taker, long nextSeq, FillGuard guard, MatchSink sink) {
  var opp = taker.side() == BUY ? asks : bids;
  while (taker.remaining() > 0 && !opp.isEmpty()) {
    var best = opp.firstEntry(); long px = best.getKey();
    if (!taker.crosses(px)) break;
    PriceLevel level = best.getValue(); Order maker = level.peekFirst();
    if (maker.accountId() == taker.accountId()) {                 // self-trade prevention: cancel RESTING order
      level.removeFirst(); index.remove(maker.id());
      if (level.isEmpty()) opp.pollFirstEntry();
      sink.onSelfTradeCancel(maker); sink.onDelta(maker.side(), px, level.total()); continue;
    }
    long qty = Math.min(Math.min(maker.remaining(), taker.remaining()), guard.maxQty(taker, px));
    if (qty == 0) break;                                           // taker cannot afford another lot
    maker.fill(qty); taker.fill(qty); level.reduce(qty);
    Order buyer = taker.side() == BUY ? taker : maker, seller = (buyer == taker) ? maker : taker;
    long escrowPx = buyer.escrowsUpfront() ? buyer.limitPrice() : 0;
    sink.onFill(new Trade(nextSeq++, taker.instrument(), maker.id(), taker.id(), buyer.accountId(),
                          seller.accountId(), taker.side(), px, qty, escrowPx));
    if (maker.remaining() == 0) { level.removeFirst(); index.remove(maker.id()); }
    if (level.isEmpty()) opp.pollFirstEntry();
    sink.onDelta(maker.side(), px, level.total());
  }
  return nextSeq;
}
```

- `FillGuard.maxQty(taker, px)`: market BUY ⇒ `free(quote) / px`; otherwise `Long.MAX_VALUE`.
- **FOK pre-check (defect 3 fix)** — read-only, **skips the taker's own resting orders** (STP would cancel them):

```java
long availableQty(Order taker) {
  long sum = 0;
  for (var lvl : oppositeLevelsBestFirst(taker.side())) {
    if (!taker.crosses(lvl.price())) break;
    for (Order o : lvl.orders()) if (o.accountId() != taker.accountId()) sum += o.remaining();
    if (sum >= taker.remaining()) return sum;
  }
  return sum;
}
```

- **FOK is allowed only on `LIMIT`** (defect 4 fix: `MARKET` must be `IOC`, so no market FOK exists).

TIF rules in `ExecutionCore.place`:

| TIF | Rule |
|---|---|
| `GTC` | match, rest remainder |
| `IOC` / `MARKET` | match, `release` remainder, never rest |
| `FOK` (LIMIT only) | `availableQty ≥ qty` else reject **before reserve**; then behaves like IOC |
| `POST_ONLY` | if `wouldCross` ⇒ reject (no state change); else rest |

### 6.3 `ExecutionCore.execute` — three phases

```java
public ExecResult execute(SignedCommand sc, EngineListener l) {
  // Phase 1: authenticate. Throws before ANY mutation. Result: AUTH_FAILED, state untouched.
  try { authenticate(sc); }
  catch (OrderRejectedException e) { l.onReject(sc.accountId(), refOf(sc.body()), e.getMessage());
                                     return new ExecResult(AUTH_FAILED, 0, e.getMessage()); }
  // Phase 2: burn nonce (deterministic, even if the business step rejects). Prevents replay of old signed commands.
  ledger.consumeNonce(sc.accountId(), sc.nonce());
  // Phase 3: business logic — atomic: either fully applied or REJECTED with no other state change.
  try { long id = switch (sc.body()) { case Register r -> ...; case Deposit d -> ...;
                                        case Cancel c -> ...; case Place p -> ...; };
        return new ExecResult(ACCEPTED, id, null); }
  catch (OrderRejectedException e) { l.onReject(...); return new ExecResult(REJECTED, 0, e.getMessage()); }
}
```

`authenticate`: chainId matches; account registered (or, for `REGISTER`, **not** yet registered and the key in the body self-certifies); `nonce > lastNonce`; signature valid over `unsigned bytes` (skip only when `verifySignatures=false`, benchmarks only). `Deposit` additionally requires `accountId == ADMIN`.

`place` order: validate (instrument exists, tick/lot multiples, `0 < price ≤ min(MAX_PRICE, maxPrice)`, `0 < qty ≤ min(MAX_QTY, maxQty)`, MARKET ⇒ IOC, FOK ⇒ LIMIT) → assign `id = nextOrderId++` → FOK/POST_ONLY pre-checks → `ledger.reserve` → `onAccepted` → `match` (settle + `onTrade` per fill) → rest or release remainder.

Also required: `ExecutionCore.copy()` (deep copy of ledger, books, counters) and `stateRoot()`, `nextTradeSeq()`.

**Block validity from the engine's view:** a command whose `execute` returns `AUTH_FAILED` makes the **whole block invalid**; a `REJECTED` command is a valid no-op (its nonce is burned deterministically). *(Defect 1.)*

### 6.4 Sequencer (single writer) — with defects 1 and 6 fixed

```java
// owned by the sequencer thread
long seqBefore = speculative.nextTradeSeq();
ExecResult r = speculative.execute(c, listener);
resultListener.onResult(c, r);                                   // ACCEPTED_PROVISIONAL / REJECTED to the gateway
if (r.status() != AUTH_FAILED) {                                 // DEFECT 1: never put auth-failed commands in a block
    if (pending.isEmpty()) pendingFirstTradeSeq = seqBefore;
    pending.add(c);
}
```

- `BlockDraft(long firstTradeSeq, List<SignedCommand> commands, byte[] stateRoot)`; cut a draft when `pending.size() ≥ maxBatch` or the cut interval elapsed, and `inflight.size() < maxInflight`. Push the draft to `inflight`.
- Stop polling `inbound` when `pending.size() ≥ 2*maxBatch` (backpressure).
- Control queue messages: `Committed(height)` → pop the oldest inflight draft; `Rollback(freshCopy, toReplay)` → **`onRevoke(inflight.isEmpty() ? pendingFirstTradeSeq : inflight.peekFirst().firstTradeSeq())`** *(defect 6)*, `speculative = freshCopy`, clear `inflight`/`pending`, then replay `toReplay` through `execute` with the same appending rule.
- The wall clock only decides *when to cut*. It never affects execution.
- `submit()` is `inbound.offer(c)`; `false` ⇒ gateway answers `BUSY`.

### 6.5 AuditTap (defect 5 fixed)

`EngineListener.onTrade` → bounded SPSC queue (`OneToOneConcurrentArrayQueue`) with **drop-on-full + `droppedCount`** → `audit-logger` thread → `PipedOutputStream`/`PipedInputStream` (legacy demo path, separate reader thread). The pipe write can block, so it must **never** be called from the sequencer.

### 6.6 Market data (M5)

- Engine `onBookDelta`/`onTrade` → SPSC queue → `MarketDataThread` owning its own `DepthBook` (aggregated price → qty per side). **Never read `OrderBook` from that thread.**
- UDP packet (≤ 1,200 bytes): `[type:1][seq:8][instrument:2][nBids:2][nAsks:2]` then `(price:8, qty:8)*`. Types: `1=DEPTH`, `2=REVOKE(fromTradeSeq:8)`, `3=HEARTBEAT`. **At most 35 levels per side** ⇒ `15 + 16×70 = 1,135` bytes.
- Subscribers detect `seq` gaps → request a TCP `SNAPSHOT` (`seq` + full depth), discard buffered packets with `seq ≤ snapshotSeq`, apply the rest.

### 6.7 Persistence and recovery (M6)

- **Block log** records: `[len:4][crc32c:4][Committed bytes]`; fsync on commit (`FileChannel.force(true)`).
- **Snapshots** every K blocks: write `StateCodec` bytes + height + root to a temp file, fsync, atomic rename.
- **Recovery:** scan the log, validate CRCs, **truncate a torn tail**, **refuse mid-log corruption** with a clear error; load the latest valid snapshot (verify its root), replay later blocks through a fresh `ExecutionCore`, assert each replayed `stateRoot == header.stateRoot`; resume.
- **Consensus safety state** (`ConsensusWal`, M7): persist `(height, round, lockedRound, lockedBlockHash, lockedBlockBytes, lastVoteSignBytes)` with temp-file + fsync + rename **before sending any vote or proposal**. On restart never contradict it.

### 6.8 Consensus (M7–M8): `docs/protocol.md` first

Before writing any consensus code, write `docs/protocol.md` from the Tendermint paper (Buchman, Kwon, Milosevic — *The latest gossip on BFT consensus*): message types, state variables, every rule with its trigger, timeouts, locking/valid-block rules, proposer selection, evidence, and the safety/liveness arguments you rely on. Have one person review it. The checklist below is the intent, not a substitute.

State per height: `round, step ∈ {PROPOSE, PREVOTE, PRECOMMIT}, lockedBlock/lockedRound (-1), validBlock/validRound (-1)`.

1. Start round `r`: proposer `(height + r) mod n` proposes `validBlock` if set, else a block from the sequencer draft. Others start `timeoutPropose(r)`.
2. On `PROPOSAL` in PROPOSE: if block valid **and** (`lockedRound == -1` or `lockedBlock == block`) → PREVOTE block, else PREVOTE nil.
3. 2f+1 PREVOTEs for the block (step ≥ PREVOTE): `validBlock = block`; if step == PREVOTE → lock, PRECOMMIT block.
4. 2f+1 PREVOTE nil → PRECOMMIT nil. 2f+1 PREVOTE any → `timeoutPrevote` → PRECOMMIT nil.
5. 2f+1 PRECOMMIT any → `timeoutPrecommit` → round `r+1`.
6. Proposal + 2f+1 PRECOMMITs for the same block at any round of this height ⇒ **COMMIT**: persist block + cert (fsync) → apply to committed core → `Committed` to the sequencer → `FinalityListener.onFinal` → next height.
7. `timeout(r) = base + r×delta`. Verify vote signatures and validator indexes; log **equivocation evidence**.

**Block validity (defect 2 fixed):**

| Check | When |
|---|---|
| correct proposer for `(height, round)`; `prevHash == own head`; `txRoot` matches commands; size/count limits | always |
| every command decodes; replay on `committed.copy()` yields **no `AUTH_FAILED`** and `stateRoot == header.stateRoot` | always |
| `timestamp > prev.timestamp` | always |
| `|timestamp − localNow| ≤ 5s` | **live voting only** (never during sync or replay) |
| commit cert has ≥ 2f+1 distinct valid validator signatures | sync / replay |

A validator never mutates its committed core while validating; it replays on a copy.

**Signature cost (defect 7):** `CachingVerifier` caches positive results keyed by `SHA256(pubKey || unsignedBytes || signature)`, bounded (≈ 2¹⁸ entries, FIFO eviction), thread-safe. It is performance-only, never consensus state. Benchmark verification with and without the cache as its own result.

### 6.9 Threads

| Thread | Type | Owns |
|---|---|---|
| `sequencer` | dedicated platform, `BackoffIdleStrategy` | speculative core |
| `gateway-*` | virtual, one per connection | socket I/O, signature pre-check |
| `consensus` | dedicated platform, single event loop | committed core, consensus state, block store writes |
| `marketdata` | dedicated platform | `DepthBook`, UDP send |
| `audit-logger` | daemon platform | piped-stream reader |
| `gossip-udp`, `sync-*` | platform / virtual | heartbeats, block sync |
| `sim-traders` | platform in a named `ThreadGroup` | load generator |
| JavaFX Application Thread + one virtual UDP listener | | UI |

---

## 7. Defect registry (fix in the stated milestone; regression test must fail without the fix)

| # | Defect | Fix | Milestone | Regression test name |
|---|---|---|---|---|
| 1 | Sequencer put auth-failed commands in blocks ⇒ validators vote nil ⇒ liveness bug | append only non-`AUTH_FAILED` commands; block with an auth-failed command is invalid; business rejects valid | M3 | `SequencerTest.authFailedCommandNeverEntersBlock`, `BlockValidityTest.authFailedInBlockIsInvalid` |
| 2 | Wall-clock bound in block validity broke sync/replay | apply wall-clock bound only when voting live | M7 | `BlockValidityTest.oldBlockValidDuringSync` |
| 3 | FOK pre-check counted own resting orders ⇒ partial fill after STP | skip same-account orders in `availableQty` | M2 | `FokTest.ownRestingLiquidityDoesNotCount` |
| 4 | FOK on market BUY could pass pre-check then partial-fill under `FillGuard` | `MARKET ⇒ IOC`, `FOK ⇒ LIMIT` | M2 | `FokTest.marketFokRejected` |
| 5 | `PipedOutputStream` can block the sequencer | SPSC queue (drop + counter) → audit thread → pipe | M3 | `AuditTapTest.stalledReaderNeverBlocksEngine` |
| 6 | `onRevoke(0)` placeholder | track `firstTradeSeq` per draft; revoke from the oldest uncommitted | M6 | `RollbackTest.revokeStartsAtOldestUncommittedTrade` |
| 7 | Signature re-verification cost unstated | `CachingVerifier`; benchmark separately | M7 / M10 | `CachingVerifierTest.*`, JMH `VerifyBench` |
| 8 | Retry after missed ack returned only `StaleNonceException` | `ResultCache` + `QUERY_STATUS` | M4 | `GatewayTest.retryAfterLostAckReturnsOriginalResult` |
| 9 | `Ledger.release` used raw `*` | `multiplyExact` everywhere + ArchUnit rule | M2 | `ArchRulesTest.noRawMultiplyOnMoney` |

---

## 8. Milestones, task cards, and gates

Ten milestones, ≈ 22 weeks part-time. **Core tier = M1–M6** (a complete project). **Extended tier = M7–M10**. Don't start the Extended tier until Core's gates are green.

Task-card fields: **Files** · **Do** · **Test** · **Verify** (command that must be green).

---

### M1 — Foundations (weeks 1–2)

- [ ] **M1-T1 Scaffold.** Files: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, Gradle wrapper, `.editorconfig`, `.gitignore`. Do: modules per §4.1 (empty `package-info`), Java 21 toolchain, Spotless, Error Prone, `-Xlint:all` + warnings-as-errors in CI, JaCoCo, tags `slow`/`recovery`/`sim`, tasks from §3.1, `checkModuleGraph` per §4.2. Test: temporary bad dependency edge makes `checkModuleGraph` fail. Verify: `./gradlew build checkModuleGraph`.
- [ ] **M1-T2 CI.** Files: `.github/workflows/ci.yml`, `nightly.yml`. Do: `ci.yml` runs `build` on push/PR; `nightly.yml` (cron) runs `nightly`, `simSweep`, `recoveryTest`. Test: on a throwaway branch add a planted failing test, confirm CI is red, then remove it. Verify: green CI on `main`.
- [ ] **M1-T3 Core types.** Files: all of §5.4, `Limits`, exceptions §5.2, `GenesisConfig`, `Instrument`. Do: implement exactly as specified. Test: compile + construction tests. Verify: `./gradlew :mizan-core:test`.
- [ ] **M1-T4 `CommandCodec`.** Files: `core/CommandCodec.java`, tests. Do: §5.3 encode/decode with every validation in the decode rules. Test: jqwik round-trip; fuzz ≥ 100k random byte arrays; truncated and extended valid frames at **every length**; only `ProtocolException` escapes. Verify: `./gradlew :mizan-core:test`.
- [ ] **M1-T5 Crypto.** Files: `crypto/{Hashing,SimpleMerkle,Signer,SignatureVerifier,CachingVerifier}.java`. Do: SHA-256 helper, `SimpleMerkle` per §5.6, Ed25519 wrappers with X.509 key bytes, `CryptoException`. Test: Merkle known-answer, odd counts, leaf-vs-node tag separation; sign/verify, tampered message, wrong key, wrong chainId (via sign-bytes). Verify: `./gradlew :mizan-crypto:test`.
- [ ] **M1-T6 Test kit seeds.** Files: `testkit/{Fixtures,SignedCommands}.java`. Do: deterministic key fixtures from fixed seeds (use `SecureRandom` seeded only in tests via a fixed `KeyPair` encoding, or generate once and store test vectors), `SignedCommands.sign(...)`, genesis fixture. Verify: `./gradlew :mizan-testkit:test`.
- [ ] **M1-T7 Docs.** Files: `docs/assumptions.md`, `docs/adr/0001-single-writer.md`, `0002-block-log-is-wal.md`, `0003-nonce-idempotency.md`, `PROGRESS.md`, `README.md` stub. Do: write §1, §5.5 durability and idempotency decisions. Verify: files exist and are linked from README.

**Gate M1:** fresh clone `./gradlew build` is green; CI turns red on a planted failure; `checkModuleGraph` enforced; codec fuzz ≥ 100k cases yielding only `ProtocolException`.

---

### M2 — Engine (weeks 3–5)

- [ ] **M2-T1 Orders.** Files: `engine/{Order,LimitOrder,MarketOrder}.java` (sealed). Do: §5.4 hierarchy with `escrowsUpfront()`, `rests()`, `crosses()`. Test: unit tests per type incl. MARKET⇒IOC validation. Verify: `./gradlew :mizan-engine:test`.
- [ ] **M2-T2 `PriceLevel`.** Do: `ArrayDeque<Order>` + running `total`, API: `peekFirst, removeFirst, remove(order), add, reduce(qty), total, isEmpty, orders()` (iteration in FIFO order). Test: invariants under random ops.
- [ ] **M2-T3 `OrderBook`.** Do: §6.2 `match`, `rest`, `cancel(orderId, account)` (owner check), `availableQty` (defect 3), `wouldCross`, `depth(side, n)`, `copy()`, deterministic `orders()`. Test: unit + `FokTest.ownRestingLiquidityDoesNotCount`.
- [ ] **M2-T4 `Ledger`.** Do: §6.1 exactly (`peek`, `move`, fees, nonces, conservation helpers `total(asset)`, `copy()`). Defect 9: all money math `multiplyExact`. Test: unit tests per operation; overflow cases.
- [ ] **M2-T5 `ExecutionCore`.** Do: §6.3 three-phase `execute` returning `ExecResult`; validation; TIF; STP via `MatchSink`; `FillGuard`; `copy()`; `nextTradeSeq()`. Test: scenario tests for each TIF and error path; `FokTest.marketFokRejected`.
- [ ] **M2-T6 Genesis bootstrap.** Do: accounts 0 and 1, instruments → empty books, admin pubkey registered.
- [ ] **M2-T7 State root v1.** Do: `SimpleStateRoot` over §5.6 leaves with `SimpleMerkle`; `stateRoot()` in `ExecutionCore`.
- [ ] **M2-T8 `StateCodec`.** Do: canonical serialization of entire state (ledger, meta, books by price/FIFO, counters) + decode; round-trip. Test: `decode(encode(core)).stateRoot() == core.stateRoot()` after random streams.
- [ ] **M2-T9 `ReferenceEngine` (testkit).** Do: an *independent*, naive implementation (plain lists re-sorted per match, per-account balance map, no shared code with `OrderBook`/`Ledger`) plus `StateDump` (canonical sorted dump of balances, meta, book levels) and `Differential` harness comparing **`ExecResult`, emitted events, and full `StateDump` after every command**. Test: harness fails on a deliberately injected bug (mutation check).
- [ ] **M2-T10 Properties.** Do: jqwik generators over a small universe (≈5 accounts, 2 instruments, prices 95–105, qty 1–20, all TIFs, ~10% invalid commands, ~10% cancels). Properties: conservation (incl. fee account), replay (two fresh cores ⇒ identical root after every command), copy independence, no crossed book, no empty levels, FIFO by `orderId`, index consistency, **rejection purity** (root diff == nonce change only), price-improvement refund, market-buy guard (never negative), STP, fee conservation, partial-fill-then-cancel escrow release, replay protection, differential equality.
- [ ] **M2-T11 Arch rules.** Do: `ArchRulesTest` banning clock/`Random`/threads/I-O/unordered iteration and raw `*` on money fields in the determinism packages. Includes `ArchRulesTest.noRawMultiplyOnMoney` (defect 9).

**Gate M2:** conservation, replay, FIFO, rejection-purity properties pass at `-Pjqwik.tries=100000` (`./gradlew nightly`); differential test equal after every command; ≥ 90% line / 85% branch coverage on `engine`; ArchUnit rules green.

---

### M3 — Sequencer + baseline (weeks 6–7)

- [ ] **M3-T1 `Sequencer`.** Files: `engine/Sequencer.java`. Do: §6.4 (Agrona MPSC `inbound`, control queue, `BackoffIdleStrategy`, cut policy, `BlockDraft`, `ResultListener`, defect 1). Rollback handling implemented against a fake chain layer. Test: many producers → no lost/duplicated commands, executed order == dequeue order; `SequencerTest.authFailedCommandNeverEntersBlock`; queue-full ⇒ `submit` false and sequencer unaffected.
- [ ] **M3-T2 `AuditTap`.** Files: `sim/AuditTap.java`. Do: §6.5. Test: `AuditTapTest.stalledReaderNeverBlocksEngine`; drop counter increments when the queue fills.
- [ ] **M3-T3 `Mempool<T>`.** Files: `sim/Mempool.java`. Do: bounded, `ReentrantLock` + `Condition`, de-dup by `(account, nonce)`, `add`, `drain(max, timeoutMs)`. Role: re-queue after rollback and forwarding buffer for non-proposers — **not** the hot path. Test: concurrent add/drain, capacity, timeout wake-up, duplicate rejection.
- [ ] **M3-T4 JMH harness.** Files: `bench/*`. Do: `execute` throughput/latency (signatures off and on), book add/cancel/match by depth. Use pre-signed commands. Verify: `./gradlew :mizan-bench:jmh`.
- [ ] **M3-T5 Publish baseline.** Files: `docs/bench/baseline.md` + raw output. Do: hardware, JDK build, flags, GC, JMH settings, p50/p99/p99.9/max (max labelled "observed"). **No optimization yet.**

**Gate M3:** no lost/duplicated commands under many producers; BUSY on full queue; baseline published with full methodology.

---

### M4 — TCP gateway (weeks 8–9)

- [ ] **M4-T1 `FrameCodec`.** Do: §5.5 framing, `MAX_FRAME`. Test: feed frames through a wrapper stream that delivers **1 byte at a time and in random chunk sizes**; coalesced frames; every byte boundary.
- [ ] **M4-T2 `TcpGateway`.** Do: virtual thread per connection; decode → `CachingVerifier` pre-check (drop junk before the sequencer) → `CommandSink.submit` → `BUSY` or wait for `ResultListener` → `ACCEPTED_PROVISIONAL`/`REJECTED`; later `FinalityListener` → `FINAL`. Push `TRADE`/`REVOKE` to the owning connection's account (small outbound queue, drop-oldest, documented).
- [ ] **M4-T3 `ResultCache` + `QUERY_STATUS`.** Do: §5.5 (defect 8). Test: `GatewayTest.retryAfterLostAckReturnsOriginalResult`; bounded size; LRU eviction.
- [ ] **M4-T4 Hardening.** Do: read timeouts, max connections, per-connection and per-account token-bucket rate limits, repeated-protocol-error disconnect. Start `docs/threat-model.md` stub. Test: slow-loris/half-frame clients, oversized frames, garbage ⇒ `ERR` + disconnect, server stays up.
- [ ] **M4-T5 Client + load.** Files: `sim/{MizanClient,LoadGenerator}.java`, `node` CLI `mizan load`. Do: `LoadGenerator` in `ThreadGroup("sim-traders")`, shared `AtomicLong` for client order ids, HdrHistogram latency, prints `group.activeCount()` after join. Test: 100 traders × 1,000 orders, conservation holds afterwards.

**Gate M4:** framing tests pass at every byte boundary; slow-loris safe; backpressure test (flood > queue) yields `BUSY` with bounded latency and no OOM; **10-minute soak with flat heap and zero leaked threads**.

---

### M5 — Market data (week 10)

- [ ] **M5-T1** Engine listener → `OneToOneConcurrentArrayQueue<MarketEvent>` (sequencer sole producer).
- [ ] **M5-T2 `MarketDataThread` + `DepthBook`.** §6.6. Test: **`DepthBook` equals `OrderBook.depth()`** after random streams (compare on the sequencer thread in the test).
- [ ] **M5-T3 UDP publisher/subscriber (`MarketDataClient`).** Packet layout and 35-level cap from §6.6; gap detection; TCP snapshot; REVOKE handling. Test: a lossy/reordering UDP proxy at 10% loss — subscriber converges to the exact depth.
- [ ] **M5-T4 Minimal JavaFX ladder.** `ui/DashboardApp`: bid/ask `ListView`s, last trades, status bar (gap/revoke), virtual-thread UDP listener, `Platform.runLater` marshalling. Test: view-model unit tests (no UI harness required).

**Gate M5:** subscriber converges to exact depth at 10% loss + reordering; `DepthBook == OrderBook.depth()` on random streams; the UI shows a live book under load and never blocks the FX thread.

---

### M6 — Persistence + recovery (weeks 11–13)

- [ ] **M6-T1 Blocks.** `chain/{BlockHeader,Block,CommitCert}`, `blockHash`, `txRoot`, canonical codecs per §5.7. Test: round-trip; tamper detection.
- [ ] **M6-T2 Genesis loader + height-0 anchor.** Jackson in `node`; `Genesis.stateRoot()`.
- [ ] **M6-T3 `BlockStore`.** §6.7 format, fsync, torn-tail truncation, mid-log corruption refusal. Test: torn tail and bit flips.
- [ ] **M6-T4 `SnapshotStore`.** Temp file + fsync + atomic rename every K blocks.
- [ ] **M6-T5 `Recovery`.** §6.7 procedure; asserts each replayed root equals the header's.
- [ ] **M6-T6 `ConsensusEngine`, `AbstractConsensus`, `SingleNodeConsensus`.** `ConsensusEngine { CompletableFuture<Block> propose(...); onProposal; onVote }`; `AbstractConsensus` holds `n`, `f()`, `quorum()`, `proposerFor`. `SingleNodeConsensus` self-signs a 1-of-1 cert.
- [ ] **M6-T7 Commit path + FINAL.** Draft → consensus → fsync → committed core applies block (collecting `ExecResult`s) → `Committed` control to sequencer → `FinalityListener.onFinal` → gateway sends `FINAL`. Rebuild `ResultCache` from the last K blocks on startup.
- [ ] **M6-T8 Speculative/committed split + rollback.** Test hook that fails a draft: `RollbackTest.revokeStartsAtOldestUncommittedTrade` (defect 6), commands replayed, `onRevoke` exactly once.
- [ ] **M6-T9 Node wiring.** `mizan node --genesis <file> --data <dir>`; graceful shutdown.
- [ ] **M6-T10 Crash test.** `@Tag("recovery")`: fork a child JVM running the node + load, kill it at **named fault points** (before append, mid-append, after append before snapshot, mid-snapshot, mid-block) and at random times; restart; assert the recovered `stateRoot` equals an uninterrupted run for the same committed prefix; clients' `FINAL` results survive. Verify: `./gradlew recoveryTest -Piter=200`.

**Gate M6:** **200 randomized kill/restart iterations pass** with recovered root equal to an uninterrupted run; torn tail truncated; mid-log corruption refused; replay on a fresh node gives identical roots at every height.

> **End of Core tier.** Before continuing: publish the README first screen with three linked numbers (recovery iterations passed, p99 latency on stated hardware, and — once M7 exists — fault seeds passed).

---

### M7 — Consensus, fixed proposer (weeks 14–16)

- [ ] **M7-T1 `docs/protocol.md` (week 1, before code).** §6.8; reviewed by another person.
- [ ] **M7-T2 SPI + simulator.** `chain/{Network,Clock,Scheduler}`; `sim/{SimWorld,SimNetwork,VirtualScheduler}` (single-threaded event queue ordered by `(time, order)`; seeded `SplittableRandom`; drop, duplicate, random delay ⇒ reordering, partition/heal).
- [ ] **M7-T3 Messages.** `Proposal`, `Prevote`, `Precommit` canonical codecs, signature checks, validator registry from genesis.
- [ ] **M7-T4 `RoundBft`.** §6.8 as a **single-threaded event loop** (same code under `SimWorld` and TCP); fixed proposer (the sequencer node).
- [ ] **M7-T5 `ConsensusWal`.** §6.7 safety state persisted with fsync before any vote/proposal. Test: restart at every step never produces a conflicting vote.
- [ ] **M7-T6 Block validity.** §6.8 table (defect 2: `BlockValidityTest.oldBlockValidDuringSync`); `CachingVerifier` reused (defect 7).
- [ ] **M7-T7 `TcpNetwork`.** Persistent peer connections, reconnect/backoff, `FrameCodec`, inbox feeding the consensus loop. Smoke test on localhost.
- [ ] **M7-T8 Cluster + evidence.** `mizan cluster --nodes 4`, optional `docker-compose.yml`; equivocation evidence log.
- [ ] **M7-T9 Sweep tests.** `@Tag("sim")`; scenarios: n=4 happy path, 1 crashed validator, Byzantine conflicting voter, 10–30% drop with 5–50 ms jitter + duplicates + reordering. Invariant: no two honest nodes commit different blocks at one height. Verify: `./gradlew simSweep -Pseeds=1000`.

**Gate M7:** **no conflicting commits across 1,000 seeds × {crash, Byzantine voter, 10–30% loss}**; liveness after noise stops; the same code passes a localhost TCP smoke test.

---

### M8 — Rotation, sync, gossip (weeks 17–19)

- [ ] **M8-T1** Proposer rotation `(height + round) mod n`; proposer builds from `validBlock` if locked.
- [ ] **M8-T2** Command forwarding to the current proposer; buffered commands to `Mempool` and re-forwarded on proposer change (nonce de-dup makes it idempotent).
- [ ] **M8-T3** Optimistic execution only on the current proposer: on losing proposer role, drop speculative and re-seed from committed; on gaining it, `speculative = committed.copy()`; emit `REVOKE`.
- [ ] **M8-T4** TCP block sync (`SYNC_REQ(fromHeight, max)`): verify each block's cert (≥ 2f+1 distinct valid sigs), `prevHash` chain, replay root; ban peers serving bad blocks.
- [ ] **M8-T5** UDP `HEARTBEAT{nodeId, height, headHash}` ≈ every 500 ms (advisory only; sync verifies certs); higher peer height triggers sync; 50% loss tolerated.
- [ ] **M8-T6** Late-joiner and restarted-validator catch-up; light verification helper (block + cert vs genesis validators).
- [ ] **M8-T7 Fault matrix (`@Tag("sim")`).** Proposer crash at every phase; 2/2 partition then heal; delay/duplicate/reorder; sequencer failover: **client-observed `FINAL` trades equal the chain's trades**.

**Gate M8:** matrix passes across 1,000 seeds; no fork; liveness resumes after heal; zero divergence between committed trades and client-seen `FINAL` trades.

---

### M9 — Hardening (week 20)

- [ ] **M9-T1** Metrics (counters + HdrHistograms: ingest→ack, execute, cut→commit, queue depths, BUSY, rollbacks, round bumps) with plain-text `/metrics` via JDK `HttpServer`.
- [ ] **M9-T2** Structured logging for node/consensus lifecycle (engine stays silent).
- [ ] **M9-T3** `docs/threat-model.md` (signatures, chainId, nonces, frame/rate limits, no `ObjectInputStream`, dependency scan, log injection), `docs/runbook.md`.
- [ ] **M9-T4** `mizan verify-chain` (re-verify a data dir from genesis via certs + replay), `keygen`, `faucet`.
- [ ] **M9-T5** Structure-aware fuzzing of consensus messages and block bytes (never crash, only reject).
- [ ] **M9-T6** UI chain panel (height, head hash, state root, validator liveness), depth chart, REVOKE indicator; multi-instrument selector; run 2–3 instruments in one chain.
- [ ] **M9-T7** Chaos run: random crashes/partitions on 4 nodes under load for 30 minutes; per-node roots at each height compared.

**Gate M9:** 30-minute chaos run with no divergence, no invariant violations, all nodes converge.

---

### M10 — Optimize + release (weeks 21–22)

Profile first (async-profiler/JFR). One commit per optimization, each with a **before/after row on the same machine** in `docs/bench/results.md`.

- [ ] **M10-T1** Intrusive doubly-linked `PriceLevel` (O(1) cancel), Agrona `Long2ObjectHashMap` for `index`. Differential + property tests must still pass unchanged.
- [ ] **M10-T2** `SparseMerkleTree` incremental root (§5.6, `stateRootVersion = 2`); benchmark vs full recompute at 10², 10³, 10⁴, 10⁵ accounts.
- [ ] **M10-T3** GC comparison (Parallel vs ZGC vs Shenandoah) with p99.9.
- [ ] **M10-T4** Signature verification: with/without `CachingVerifier`, batch across virtual threads; separate result.
- [ ] **M10-T5 (conditional)** Object pooling for `Order`/`Trade` only if the profile shows allocation pressure, with a before/after row.
- [ ] **M10-T6 (optional)** Undo journal replacing `copy()` for validators (keep `copy()` as the reference and cross-check); group commit for the block store.
- [ ] **M10-T7** README: first screen = three linked numbers; architecture diagram; determinism rules; threat model link; trade-offs (single writer vs instrument sharding; deviations from textbook Tendermint; UDP vs TCP); quick start; **limitations**.
- [ ] **M10-T8** 5-minute demo recording: crossing orders, FIFO, cancel releasing escrow, `kill -9` and recovery to the same root, proposer crash with no fork.
- [ ] **M10-T9** Tag `v1.0.0`; publish `engine`, `crypto`, and the `SimNetwork` harness (GitHub Packages first, Maven Central later); changelog.

**Gate M10:** ≥ 3 optimizations each with a before/after row; a stranger runs the 4-node demo from the README; all earlier gates still green.

---

## 9. Test infrastructure details

- **`ReferenceEngine`** shares **no code** with `OrderBook`/`Ledger` (otherwise it proves nothing). It may be slow. It compares `ExecResult`, event streams, and `StateDump` after every command.
- **Generators** use a small universe so interesting collisions (STP, partial fills, escrow release) occur often; include ~10% invalid/hostile commands (bad sig, stale nonce, unknown instrument, overflow-sized values).
- **Seeds:** all randomized tests derive from one printed `SEED`; failures print `./gradlew <task> -Pseed=<n>` to reproduce. Shrunk jqwik failures are stored under `testkit/src/test/resources/regressions/`.
- **Crash tests** use a real child JVM (`ProcessBuilder`), `Process.destroyForcibly()`, and named fault-injection hooks gated by a system property (compiled out of production paths).
- **Consensus tests** run entirely on `SimWorld` virtual time; no `Thread.sleep`.

## 10. Evidence and benchmark rules

Every README claim must link to a re-runnable artifact.

| Layer | Technique | Artifact |
|---|---|---|
| Codec/frames/messages | round-trip + structure-aware fuzz | case count in CI log |
| Matching + ledger | jqwik properties | nightly at 100k tries, stored seeds |
| Optimized vs naive | differential vs `ReferenceEngine` | equality after every command |
| Concurrency | many-producer stress (+ jcstress optional) | lost/duplicate counts = 0 |
| Recovery | child-JVM kill at named fault points | 200-iteration report |
| Consensus | `SimWorld` seed sweeps | 1,000-seed matrix, replay command on failure |
| Whole stack | chaos run | per-node roots per height |
| Performance | JMH `SampleTime` + HdrHistogram | `docs/bench/` raw output |

**Benchmark rules:** report p50/p99/p99.9 and max (labelled "observed", not a bound), never averages alone. Benchmark **separately**: matching; block-log fsync; gateway round trip; state-root cost vs account count; signature verification; consensus commit latency. Publish CPU model, cores, RAM, OS, JDK build, flags, GC, JMH settings. **Set no targets before the M3 baseline**; afterwards set improvement goals from the profile. A single-core engine with signatures off reaching hundreds of thousands of commands/s is plausible, but publish whatever you measure.

---

## 11. Cut order, never-cut list, definition of done

**Cut order if behind:** validator-set changes → undo journal → group commit → JavaFX polish → M9 chaos duration.
**Never cut:** determinism rules; property and differential tests; crash-recovery tests; seeded fault sweeps; the M3 baseline.

**Project definition of done**

- [ ] Core tier gates (M1–M6) green; Extended tier gates green or explicitly listed as unbuilt in the README.
- [ ] README first screen: three linked numbers (recovery iterations passed; fault seeds passed or "not built"; p99 latency on stated hardware).
- [ ] Limitations section lists: simulated assets only; no real-money custody or compliance; Tendermint-lite without production optimizations; static validator set; single sequencer per proposer (no instrument sharding); replace = cancel-and-new; no network-level DoS protection beyond basic limits; benchmark numbers specific to stated hardware.
- [ ] 5-minute demo recorded.

---

## 12. Merged decisions (Doc A vs Doc B)

| Topic | Decision |
|---|---|
| Build order | Doc B's ten milestones, split into Core (M1–M6) and Extended (M7–M10) |
| Idempotency | Nonce is the key **plus** bounded `ResultCache` and `QUERY_STATUS` |
| Ack levels | `BUSY`, `ACCEPTED_PROVISIONAL`, `REJECTED`, `FINAL` |
| Durability | Block log is the WAL; only `FINAL` survives a crash (ADR 0002) |
| Reference engine | Naive `ReferenceEngine` in `testkit`, differential on every command |
| Replace order | Cancel-and-new only; loses priority; documented |
| Crypto | JDK only behind `Signer`/`SignatureVerifier` |
| Consensus spec | `docs/protocol.md` written and reviewed before M7 code |
| Consensus persistence | Safety state fsynced before any vote |
| UDP sizing | ≤ 1,200 bytes ⇒ ≤ 35 levels per side |
| Object pooling | Conditional on profile evidence; needs a before/after row |
| Course topics | Kept (§14) but off the hot path and not the README headline |

---

## 13. `PROGRESS.md` template

```markdown
# Progress
Current milestone: M1   Last gate passed: none
Decisions/ADRs: see docs/adr/

## M1
- [ ] M1-T1 Scaffold
- [ ] M1-T2 CI
...(one line per task card in §8; add notes after each: decisions, surprises, measured numbers)

## Known issues / deferred
- (none)
```

---

## 14. Appendix — course lab-topic map (kept for the course; not the README headline)

| # | Topic | Where |
|---|---|---|
| 1 | Runtime polymorphism (abstract class, interface) | sealed `Order` hierarchy (`crosses`, `rests`, `escrowsUpfront`); `ConsensusEngine` with `SingleNodeConsensus`/`RoundBft`; pattern-matching `switch` over sealed `OrderCommand` |
| 2 | Callback via interface | `TradeListener`/`EngineListener`, `ResultListener`, `FinalityListener`, `MatchSink`, `FillGuard`, `Consumer<BlockDraft>` |
| 3 | Interface + inheritance | `Order` hierarchy; `OrderRejectedException` hierarchy; `AbstractConsensus implements ConsensusEngine` |
| 4 | User-defined packages | `com.sajin.mizan.{core,crypto,engine,store,chain,net,node,sim,ui}` enforced by Gradle modules |
| 5 | Checked + unchecked exceptions | Unchecked `OrderRejectedException` family; checked `ProtocolException`, `CryptoException`, `ChainUnavailableException`, `PersistenceException` |
| 6 | Threads, thread groups | `LoadGenerator` in `ThreadGroup("sim-traders")`; dedicated sequencer/consensus/marketdata threads; virtual gateway threads |
| 7 | Inter-thread communication | `AtomicLong` ids, `ReentrantLock`/`Condition` in `Mempool`, lock-free queues; piped streams in `AuditTap` (demo path only) |
| 8 | Sockets (TCP, UDP) | TCP gateway/consensus/sync/snapshot; UDP market data and heartbeats |
| 9 | JavaFX | `DashboardApp` (ladder, trades, chain panel, depth chart) |

Interview note: piped streams and `ThreadGroup` are legacy APIs. Keep them on the demo/audit path and be ready to explain why the hot path uses a bounded lock-free queue and a single writer.
