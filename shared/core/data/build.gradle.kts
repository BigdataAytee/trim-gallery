// ARCHITECTURE.md § 3, § 4 — SQLDelight database, DataStore settings, repositories.
//
// SQLDelight rather than Room: Room's KMP support has no Kotlin/Native iOS target, and
// ARCHITECTURE.md § 4 requires one schema on every platform. Recorded in PROJECT.md.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()
    androidTarget()

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
                implementation(libs.kotlinx.datetime)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(projects.shared.core.model)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
            implementation(libs.androidx.datastore.preferences)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
        jvmTest.dependencies {
            // An in-memory driver so the shared tests exercise the real schema.
            implementation(libs.sqldelight.driver.jvm)
        }
        if (System.getProperty("os.name").startsWith("Mac")) {
            iosMain.dependencies {
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}

sqldelight {
    databases {
        create("TrimDatabase") {
            packageName.set("app.trimgallery.core.data.db")
            // Schema changes ship with a migration; the DB outlives any single release.
            verifyMigrations.set(true)
        }
    }
}

android {
    namespace = "app.trimgallery.core.data"
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
