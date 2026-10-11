# ADR 0004: Project name `mizan`, package root `com.sajin.mizan`

**Context.** The spec was drafted with the working name `chainbook` and the placeholder package `com.yourname.chainbook`.

**Decision.** The project is named `mizan`. Modules are `mizan-<module>` (e.g. `mizan-core`) and the package root is `com.sajin.mizan`. The copy of the spec in this repo has been renamed to match.

**Consequences.** Spec references to `chainbook-*` modules and the `chainbook` CLI mean `mizan-*` and `mizan`. The vote sign-bytes prefix `"CBVOTE1"` (§5.7) is a wire constant and stays unchanged.
