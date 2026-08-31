// Generated as part of the ARCHITECTURE.md § 3 module layout.
// ARCHITECTURE.md § 3 — feature/space. Space screen: total freed, run history, energy estimate.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // A plain JVM target so the shared unit tests in ARCHITECTURE.md § 14 run in CI
    // without an Android SDK.
    jvm()
    android {
        namespace = "app.trimgallery.feature.space"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // Java 17 everywhere, replacing the `compileOptions` block the AGP 9 KMP plugin
    // does not have. A toolchain rather than a per-compilation `jvmTarget`: it covers
    // the jvm() and android targets at once, and it is the idiom androidApp already
    // uses, so it is proven on this CI.
    jvmToolchain(17)

    // iOS targets are declared only on a Mac. ARCHITECTURE.md § 1 puts iOS at v1.5;
    // until then Linux CI has to configure and run the shared JVM tests without a
    // Kotlin/Native toolchain. Recorded in PROJECT.md.
    if (System.getProperty("os.name").startsWith("Mac")) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                api(projects.shared.core.ui)
                implementation(projects.shared.core.domain)
                api(projects.shared.core.model)
                api(compose.runtime)
                api(compose.foundation)
                api(compose.animation)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
