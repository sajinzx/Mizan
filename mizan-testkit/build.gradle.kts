dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-crypto"))
    implementation(project(":mizan-engine"))
    implementation(project(":mizan-chain"))

    // testkit is test/demo support: it ships JUnit extensions used by other modules' tests.
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
}
