// ARCHITECTURE.md § 3, § 11 — Compose Multiplatform design system, motion specs and
// shared screens. Every screen in the app is Compose MP; platform hosts exist only for
// share sheets, permission dialogs, document pickers and biometrics.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm()
    android {
        namespace = "app.trimgallery.core.ui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // Java 17 everywhere, replacing the `compileOptions` block the AGP 9 KMP plugin
    // does not have. A toolchain rather than a per-compilation `jvmTarget`: it covers
    // the jvm() and android targets at once, and it is the idiom androidApp already
    // uses, so it is proven on this CI.
    jvmToolchain(17)

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
