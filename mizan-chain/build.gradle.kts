dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-crypto"))
    implementation(project(":mizan-engine"))
    implementation(project(":mizan-store"))
    implementation(libs.slf4j.api)

    testImplementation(project(":mizan-testkit"))
}
