@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

group = "io.github.874wokiite"
version = "0.1.0"

kotlin {
    jvmToolchain(17)

    jvm()

    android {
        namespace = "com.afterfade.dsp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    wasmJs { browser() }

    sourceSets {
        // Tests live in commonTest only, so the same suite runs on the JVM (fast, no simulator)
        // and on the iOS simulator (proves the numbers match across platforms).
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
