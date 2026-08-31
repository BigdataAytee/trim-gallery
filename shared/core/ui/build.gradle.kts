// ARCHITECTURE.md § 3, § 11 — Compose Multiplatform design system, motion specs and
// shared screens. Every screen in the app is Compose MP; platform hosts exist only for
// share sheets, permission dialogs, document pickers and biometrics.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm()
    androidTarget()

    if (System.getProperty("os.name").startsWith("Mac")) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                api(compose.runtime)
                api(compose.foundation)
                api(compose.animation)
                api(libs.compose.ui)
                implementation(libs.lifecycle.viewmodel.compose.mp)
                implementation(libs.navigation.compose.mp)
                api(projects.shared.core.model)
                // DateSections and FastScroll work in local dates.
                implementation(libs.kotlinx.datetime)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "app.trimgallery.core.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
