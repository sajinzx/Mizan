# ADR 0007: Placement and small additions to the core types

**Context.** §5.2 lists `CryptoException` among the core exceptions, but `mizan-crypto` may not depend on `mizan-core` (§4.2). The `REJECTED` frame (§5.5) needs a stable `reasonCode`.

**Decision.**
- `CryptoException` (checked) lives in `com.sajin.mizan.crypto`.
- `OrderRejectedException` is `sealed`. Each subclass exposes a stable `reasonCode()`: 1 InvalidOrder, 2 InsufficientBalance, 3 LimitExceeded, 4 InvalidSignature, 5 StaleNonce. Rejects carry no stack trace because they are expected control flow.
- `Accounts.FEE_ACCOUNT = 0` and `Accounts.ADMIN_ACCOUNT = 1` are named constants.
- Records holding `byte[]` suppress Error Prone's `ArrayRecordComponent` and do not copy their arrays (§0.4). Callers must treat the arrays as immutable.
- `Instrument` and `GenesisConfig` validate themselves on construction and throw `IllegalArgumentException`. This is config validation, not a deterministic command reject.

**Consequences.** Wire reason codes are frozen once M4 ships.
