dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-chain"))
    implementation(libs.slf4j.api)
    implementation(libs.agrona)

    testImplementation(project(":mizan-testkit"))
}
