# ADR 0005: Gradle 9.x and JUnit 5.14 instead of the literal spec versions

**Context.** The spec names Gradle 8.x. The development machine has only JDK 26 installed, and Gradle 8.x cannot run on it. The spec also says to "pin latest stable at project start".

**Decision.** Use Gradle 9.8.0, the newest release at least two weeks old on 2026-10-12, with a SHA-256-pinned wrapper. Compilation and tests still use a **Java 21 toolchain**, auto-provisioned by the foojay resolver. JUnit stays on 5.14.x (not 6.x) because jqwik 1.10.x is built against JUnit Platform 1.14.

**Consequences.** Contributors need any JDK that Gradle 9.8 supports to start Gradle. The bytecode and runtime target remain Java 21. Upgrading JUnit to 6 waits on a jqwik release built for Platform 6.
