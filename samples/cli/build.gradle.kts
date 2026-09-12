plugins {
    // The catalog has no `kotlinJvm` alias, so the id is spelled out with the catalog's version.
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin.get()
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.github.874wokiite:afterfade-dsp:0.1.0")
}

application {
    mainClass.set("com.afterfade.dsp.cli.Main")
    applicationName = "afdsp"
}

// Paths on the command line are then relative to the repository root, which is where
// `./gradlew -p samples :cli:run` is typed from.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir.parentFile
}
