// Sample programs built on top of the library. This is a separate Gradle build so that the
// library root stays minimal for consumers that pull it in with includeBuild.
//
//   ./gradlew -p samples :cli:run --args="tempo path/to/file.wav"
//   ./gradlew -p samples :playground:wasmJsBrowserDevelopmentRun
rootProject.name = "afterfade-dsp-samples"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

includeBuild("..")

include(":cli")
include(":playground")
