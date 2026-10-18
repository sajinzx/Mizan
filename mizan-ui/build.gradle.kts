plugins {
    application
    id("org.openjfx.javafxplugin")
}

dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-net"))
    implementation(project(":mizan-node")) // the in-process sandbox (ADR 0017)
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls")
}

application {
    mainClass.set("com.sajin.mizan.ui.Launcher")
    applicationDefaultJvmArgs = listOf("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED", "-Xmx1g")
}
