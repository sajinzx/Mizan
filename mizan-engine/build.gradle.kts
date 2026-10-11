dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-crypto"))
    implementation(libs.agrona)

    testImplementation(project(":mizan-testkit"))
    testImplementation(libs.archunit.junit5)
}

// Gate M2: >= 90% line and >= 85% branch coverage on the engine.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}
tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
