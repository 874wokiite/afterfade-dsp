@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

// Browser playground: Kotlin/Wasm, plain DOM, no UI framework.
//
//   ./gradlew -p samples :playground:wasmJsBrowserDevelopmentRun    # dev server with hot reload
//   ./gradlew -p samples :playground:wasmJsBrowserDistribution      # static site in build/dist
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(17)

    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "playground.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            // Substituted by includeBuild("..") in samples/settings.gradle.kts.
            implementation("io.github.874wokiite:afterfade-dsp:0.1.0")
            // document / canvas / FileReader / Blob declarations. Web Audio is hand-written
            // in Browser.kt because it is not part of this package.
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
        }
    }
}
