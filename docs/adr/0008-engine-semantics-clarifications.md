# ADR 0008: Engine semantics the spec left open

**Context.** Implementing §6 exposed a few points where the spec text was ambiguous or contradicted itself.

**Decisions.**

1. **Supply cap.** Total deposits per asset are capped at `Ledger.MAX_SUPPLY = 2^62`, and a deposit that would exceed it is a `LimitExceededException`. Every balance and every sum of balances therefore stays below `2^63`. Settlement can never overflow, and an overflow there would be a bug (`InvariantViolationError`), not a reject that appears halfway through a match.
2. **Order ids are consumed only on acceptance.** §6.3 says "assign id → FOK/POST_ONLY pre-checks", but §2 says a reject changes nothing except the nonce, and `nextOrderId` is part of the state root. The candidate id is `nextOrderId`, and the counter is incremented only after the pre-checks and `reserve` succeed.
3. **Fee account.** Account 0 exists implicitly: it has balances but no meta entry and no key. It cannot sign, register, or receive deposits, and it has no leaf in the meta tree.
4. **Register.** `REGISTER` is fully validated during authentication (id range, not registered, nonce > 0, signature by the key in the body). It therefore never fails in the business phase. It creates the account with `lastNonce = nonce` instead of going through `consumeNonce`.
5. **Rejection kinds.** Authentication failures are `InvalidSignatureException` (wrong chain, unknown account, non-admin deposit, bad signature) or `StaleNonceException`. Values above caps are `LimitExceededException`. Everything else is `InvalidOrderException`.
6. **Event details.** User cancels emit `onCancelled(id, "USER")`; STP emits `"STP"`. `onReject`'s `ref` is the `clientOrderId` for `PLACE`, the `orderId` for `CANCEL`, the target for `DEPOSIT`, and 0 for `REGISTER`. A resting remainder emits one `onBookDelta` after matching.
7. **Validation order in `place`.** Instrument → positive values → caps → tick/lot → MARKET⇒IOC → FOK⇒LIMIT → FOK availability → POST_ONLY cross → reserve.

**Consequences.** The `ReferenceEngine` implements the same rules independently, and the differential tests hold the engine to them.
