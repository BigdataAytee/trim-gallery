import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generated as part of the ARCHITECTURE.md § 3 module layout.
// ARCHITECTURE.md § 3, § 7 — NightPass and the optimise/index steps. Pure Kotlin: knows nothing of WorkManager or BGProcessingTask, and is tested against fakes.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // A plain JVM target so the shared unit tests in ARCHITECTURE.md § 14 run in CI
    // without an Android SDK.
    jvm()
    androidLibrary {
        namespace = "app.trimgallery.core.pipeline"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        // The AGP 9 KMP library plugin has no `compileOptions`; the JVM target is a
        // property of each compilation instead.
        compilations.configureEach {
            compilerOptions.configure { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }

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
                // Milestone 5: the "stop by" setting is a wall-clock local time, so resolving
                // it to an instant needs a time zone and a calendar, not just arithmetic.
                api(libs.kotlinx.datetime)
                api(projects.shared.core.model)
                api(projects.shared.core.domain)
                api(projects.shared.engineApi)
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
