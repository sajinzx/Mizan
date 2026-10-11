# ADR 0006: Test configurations may depend on `mizan-testkit`

**Context.** Spec §4.2 lists allowed *production* edges. `testkit` depends on `engine` and `chain`, but those modules' own tests need `ReferenceEngine`, fixtures and generators from `testkit`.

**Decision.** `checkModuleGraph` checks every configuration whose name does not start with `test` against §4.2. Configurations whose names start with `test` may also depend on `mizan-testkit`. Every other edge is a violation.

**Consequences.** No production code can reach `testkit` unless §4.2 allows it (`sim`, `bench`). Project dependencies are declared as `implementation`, never `api`, so a module cannot compile against a transitive module that §4.2 does not list for it.
