plugins {
    application
    id("org.openjfx.javafxplugin")
}

dependencies {
    implementation(project(":mizan-core"))
    implementation(project(":mizan-net"))
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls")
}

application {
    mainClass.set("com.sajin.mizan.ui.Launcher")
}
