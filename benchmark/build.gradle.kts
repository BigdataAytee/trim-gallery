// ARCHITECTURE.md § 14 — Android Macrobenchmark. BUILD.md § 2.7 requires the gallery to
// stay at display refresh rate while background work runs; this is where that is
// measured rather than asserted.

plugins {
    // See androidApp/build.gradle.kts: AGP 9 supplies Kotlin and rejects `kotlin.android`.
    alias(libs.plugins.android.test)
}

android {
    namespace = "app.trimgallery.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Macrobenchmark drives a real app process; 29 matches the app's minSdk.
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":androidApp"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            matchingFallbacks += "release"
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit)
}
