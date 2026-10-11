plugins {
    // Auto-provisions the Java 21 toolchain on machines that do not have it installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mizan"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

include(
    "mizan-core",
    "mizan-crypto",
    "mizan-engine",
    "mizan-store",
    "mizan-chain",
    "mizan-net",
    "mizan-node",
    "mizan-testkit",
    "mizan-sim",
    "mizan-ui",
    "mizan-bench",
)
