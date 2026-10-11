dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-chain"))
    implementation(libs.slf4j.api)

    testImplementation(project(":mizan-testkit"))
}
