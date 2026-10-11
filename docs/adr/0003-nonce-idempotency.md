# ADR 0003: Nonce is the idempotency key, plus a bounded result cache

**Context.** A client that misses an ack cannot tell whether its command executed. Retrying with a fresh nonce could double-execute it, and retrying with the same nonce previously returned only `StaleNonceException` (defect 8).

**Decision.** Each account has a strictly increasing nonce. The engine burns the nonce of every authenticated command, even a business reject, so an old signed command can never be replayed. The gateway also keeps a node-local `ResultCache` keyed by `(account, nonce)` (LRU, ≤ 1024 per account, ≤ 1M total). It is **not** consensus state and is rebuilt on startup from the last K committed blocks. A duplicate `ORDER` whose key is cached gets the cached answer without being resubmitted, and `QUERY_STATUS` reports `unknown | provisional | rejected | final`.

**Consequences.** Retrying with the same nonce is always safe: it gets the cached result, a `StaleNonceException`, or a first execution, and never a double execution. Results evicted from the cache are still safe but less informative.
