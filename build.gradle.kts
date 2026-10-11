import com.diffplug.gradle.spotless.SpotlessExtension
import net.ltgt.gradle.errorprone.errorprone

plugins {
    base
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.errorprone) apply false
    alias(libs.plugins.jmh) apply false
}

// ---------------------------------------------------------------------------------------------
// Shared conventions for every module.
// ---------------------------------------------------------------------------------------------

/** Agrona 2.x reads jdk.internal.misc.Unsafe; every JVM that runs Agrona queues needs this. */
val agronaJvmArgs = listOf("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED")

/** Tags excluded from the default `test` task (spec §3.1). */
val longRunningTags = arrayOf("slow", "recovery", "sim")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "net.ltgt.errorprone")

    group = "com.sajin.mizan"
    version = "0.1.0-SNAPSHOT"

    configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    dependencies {
        "errorprone"(rootProject.libs.errorprone.core)

        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.jqwik)
        "testImplementation"(rootProject.libs.assertj.core)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Every warning is a build failure, locally and in CI.
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
        options.errorprone { disableWarningsInGeneratedCode.set(true) }
    }

    configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat(rootProject.libs.versions.googleJavaFormat.get())
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    // Parameters forwarded from `-P...` to every test JVM (spec §3.1, §9).
    val seed = providers.gradleProperty("seed")
    val iter = providers.gradleProperty("iter").orElse("20")
    val seeds = providers.gradleProperty("seeds").orElse("100")
    val tries = providers.gradleProperty("jqwik.tries")

    tasks.withType<Test>().configureEach {
        maxHeapSize = "1g"
        jvmArgs(agronaJvmArgs)
        systemProperty("jqwik.database", layout.buildDirectory.file("jqwik-database").get().asFile.path)
        systemProperty("mizan.gradleTask", path) // for seed replay hints
        systemProperty("mizan.iter", iter.get())
        systemProperty("mizan.seeds", seeds.get())
        if (seed.isPresent) systemProperty("mizan.seed", seed.get())
        if (tries.isPresent) systemProperty("jqwik.tries.default", tries.get())
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }

    val testSourceSet = the<SourceSetContainer>()["test"]

    tasks.named<Test>("test") {
        useJUnitPlatform { excludeTags(*longRunningTags) }
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    fun registerTaggedTest(name: String, desc: String, configure: Test.() -> Unit) =
        tasks.register<Test>(name) {
            group = "verification"
            description = desc
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            // Modules without tests for this tag must not fail the aggregate run.
            failOnNoDiscoveredTests.set(false)
            configure()
        }

    registerTaggedTest("nightly", "Property tests at -Pjqwik.tries (default 100000), including tag 'slow'.") {
        useJUnitPlatform { excludeTags("recovery", "sim") }
        if (!tries.isPresent) systemProperty("jqwik.tries.default", "100000")
    }
    registerTaggedTest("recoveryTest", "Child-JVM kill/restart tests (tag 'recovery'); -Piter=N.") {
        useJUnitPlatform { includeTags("recovery") }
    }
    registerTaggedTest("simSweep", "Seeded consensus fault sweeps (tag 'sim'); -Pseeds=N.") {
        useJUnitPlatform { includeTags("sim") }
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// checkModuleGraph: every project dependency edge must appear in spec §4.2.
// Main configurations are checked against the table; test configurations may additionally
// depend on mizan-testkit (ADR 0006).
// ---------------------------------------------------------------------------------------------

val allowedEdges: Map<String, Set<String>> = mapOf(
    "core" to setOf(),
    "crypto" to setOf(),
    "engine" to setOf("core", "crypto"),
    "store" to setOf("core", "crypto", "engine"),
    "chain" to setOf("core", "crypto", "engine", "store"),
    "net" to setOf("core", "chain"),
    "node" to setOf("core", "crypto", "engine", "store", "chain", "net", "sim"),
    "testkit" to setOf("core", "crypto", "engine", "chain"),
    "sim" to setOf("core", "crypto", "engine", "chain", "net", "testkit"),
    "ui" to setOf("core", "net"),
    "bench" to setOf("core", "crypto", "engine", "chain", "testkit"),
)

fun moduleKey(path: String): String = path.removePrefix(":").removePrefix("mizan-")

val graphViolations = mutableListOf<String>()
val graphEdges = mutableListOf<String>()

gradle.projectsEvaluated {
    subprojects.sortedBy { it.path }.forEach { p ->
        val from = moduleKey(p.path)
        val allowed = allowedEdges[from]
        if (allowed == null) {
            graphViolations += "module '${p.path}' is not listed in the allowed graph (spec §4.2)"
            return@forEach
        }
        p.configurations.sortedBy { it.name }.forEach { conf ->
            val isTestConf = conf.name.startsWith("test")
            conf.dependencies.withType<ProjectDependency>().forEach { dep ->
                val to = moduleKey(dep.path)
                val ok = to in allowed || (isTestConf && (to == "testkit" || to == from))
                val edge = "$from -> $to (${conf.name})"
                graphEdges += edge
                if (!ok) graphViolations += "forbidden edge $edge"
            }
        }
    }
}

val checkModuleGraph = tasks.register("checkModuleGraph") {
    group = "verification"
    description = "Fails if any project dependency edge is not allowed by spec §4.2."
    val violations = graphViolations
    val edges = graphEdges
    doLast {
        if (violations.isNotEmpty()) {
            throw GradleException("Module graph violations:\n  " + violations.joinToString("\n  "))
        }
        logger.lifecycle("checkModuleGraph: ${edges.size} project edges, all allowed.")
    }
}

tasks.named("check") { dependsOn(checkModuleGraph) }
