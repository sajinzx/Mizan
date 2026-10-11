plugins { id("me.champeau.jmh") }

dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-crypto"))
    implementation(project(":mizan-engine"))
    implementation(project(":mizan-chain"))
    implementation(project(":mizan-testkit"))
}

jmh { jmhVersion.set(libs.versions.jmh.get()) }
