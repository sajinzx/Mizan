plugins { id("me.champeau.jmh") }

dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-crypto"))
    implementation(project(":mizan-engine"))
    implementation(project(":mizan-chain"))
    implementation(project(":mizan-testkit"))
}

// Defaults for the published baseline (docs/bench/baseline.md).
// -Pjmh.includes=<regex> selects benchmarks; -Pjmh.quick runs a 1-fork smoke test.
val quick = providers.gradleProperty("jmh.quick").isPresent
jmh {
    jmhVersion.set(libs.versions.jmh.get())
    warmupIterations.set(if (quick) 1 else 3)
    warmup.set(if (quick) "1s" else "2s")
    iterations.set(if (quick) 1 else 5)
    timeOnIteration.set(if (quick) "1s" else "2s")
    fork.set(if (quick) 1 else 2)
    timeUnit.set("us")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("results/jmh/human.txt"))
    providers.gradleProperty("jmh.includes").orNull?.let { includes.add(it) }
    // -Pjmh.jvmArgs="-XX:+UseZGC -XX:+ZGenerational" (M10-T3 GC comparison); space-separated.
    providers.gradleProperty("jmh.jvmArgs").orNull?.let { jvmArgsAppend.addAll(it.split(" ").filter(String::isNotBlank)) }
    // -Pjmh.profilers=gc adds JMH's allocation profiler (M10-T5).
    providers.gradleProperty("jmh.profilers").orNull?.let { profilers.addAll(it.split(",")) }
    // -Pjmh.results=<name> keeps each run's output separately.
    providers.gradleProperty("jmh.results").orNull?.let {
        resultsFile.set(layout.buildDirectory.file("results/jmh/$it.json"))
        humanOutputFile.set(layout.buildDirectory.file("results/jmh/$it.txt"))
    }
}
