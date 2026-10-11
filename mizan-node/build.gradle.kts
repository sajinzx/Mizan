plugins { application }

dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-crypto"))
    implementation(project(":mizan-engine"))
    implementation(project(":mizan-store"))
    implementation(project(":mizan-chain"))
    implementation(project(":mizan-net"))
    implementation(project(":mizan-sim"))
    implementation(libs.picocli)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.databind)
    implementation(libs.hdrhistogram)
    runtimeOnly(libs.logback.classic)

    testImplementation(project(":mizan-testkit"))
    testImplementation(libs.hdrhistogram)
    testRuntimeOnly(libs.logback.classic)
}

application {
    applicationName = "mizan"
    mainClass.set("com.sajin.mizan.node.Main")
    // Agrona 2.x needs jdk.internal.misc (ADR 0010).
    applicationDefaultJvmArgs = listOf("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED")
}
