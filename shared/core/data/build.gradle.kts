import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ARCHITECTURE.md § 3, § 4 — SQLDelight database, DataStore settings, repositories.
//
// SQLDelight rather than Room: Room's KMP support has no Kotlin/Native iOS target, and
// ARCHITECTURE.md § 4 requires one schema on every platform. Recorded in PROJECT.md.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()
    androidLibrary {
        namespace = "app.trimgallery.core.data"
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
                implementation(libs.kotlinx.datetime)
                api(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                api(projects.shared.core.model)
                // Milestone 4/5: this module implements the pipeline's ports — UndoJournal,
                // OriginalLocator, NightFacts and the night queue — so the shared
                // orchestration never has to know a database exists.
                api(projects.shared.core.domain)
                api(projects.shared.core.pipeline)
                api(projects.shared.engineApi)
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
            api(libs.sqldelight.driver.android)
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
