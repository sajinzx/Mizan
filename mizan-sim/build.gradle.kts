dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-crypto"))
    implementation(project(":mizan-engine"))
    implementation(project(":mizan-chain"))
    implementation(project(":mizan-net"))
    implementation(project(":mizan-testkit"))
    implementation(libs.agrona)
    implementation(libs.hdrhistogram)
}
