rootProject.name = "afterfade-dsp-samples"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

// The library itself: `io.github.874wokiite:afterfade-dsp:0.1.0` resolves to this checkout's HEAD.
includeBuild("..")

include(":cli")

// Owned by another sample; only included once it exists.
if (file("playground").isDirectory) include(":playground")
