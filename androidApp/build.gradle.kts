import app.trimgallery.gradle.TrimGuardsPlugin
import app.trimgallery.gradle.VerifyNoInternetPermissionTask
import com.android.build.api.artifact.SingleArtifact

// ARCHITECTURE.md § 3 — androidApp. Platform engines, storage, scheduler and the host
// Activity. Everything else lives in shared/.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "app.trimgallery"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.trimgallery"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // arm64-v8a only (ARCHITECTURE.md § 10). The native metric libraries are built
        // for one ABI; a second would ship code that cannot run them.
        //
        // Expressed here and *not* also as an `abi` split. AGP rejects both at once
        // ("'arm64-v8a' in ndk abiFilters cannot be present when splits abi filters are
        // set"), and it rejects it during configuration, so the conflict failed every job
        // in the build — including the ones that never touch Android. Splits exist to
        // produce one APK per ABI; with a single ABI there is nothing to split, and
        // `abiFilters` is what every library module in this project already uses.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The metric libraries (ARCHITECTURE.md § 10). arm64 only, NEON on — the metrics
        // are the bottleneck and a scalar build is not worth shipping.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cFlags += "-march=armv8-a+simd"
                cppFlags += "-march=armv8-a+simd"
                // Without this, AGP asks ninja for every target the CMake graph defines,
                // which here means libjxl's and jpegli's fuzzers, benchmarks and command
                // line tools — none of which this app links, several of which do not build
                // for android-arm64, and all of which cost minutes. `EXCLUDE_FROM_ALL` on
                // the two `add_subdirectory` calls does not help: AGP names the targets on
                // the ninja command line, which overrides being outside `all`.
                targets += "trim_native"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = rootProject.file("shared/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Macrobenchmark needs a release-shaped, profileable build it can drive.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // The golden clips from ARCHITECTURE.md § 14 live in shared/testdata and are read by
    // the instrumented tests.
    sourceSets.getByName("androidTest") {
        assets.srcDir(rootProject.file("shared/testdata"))
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(projects.shared.engineApi)
    implementation(projects.shared.core.model)
    implementation(projects.shared.core.domain)
    implementation(projects.shared.core.data)
    implementation(projects.shared.core.pipeline)
    implementation(projects.shared.core.ui)
    implementation(projects.shared.feature.gallery)
    implementation(projects.shared.feature.search)
    implementation(projects.shared.feature.people)
    implementation(projects.shared.feature.space)
    implementation(projects.shared.feature.cleanup)
    implementation(projects.shared.feature.editor)
    implementation(projects.shared.feature.compress)
    implementation(projects.shared.feature.settings)

    // Compose comes from the Compose Multiplatform plugin, not from the androidx BOM.
    //
    // Mixing the two put two different Compose versions in one build, and the androidx BOM
    // is what dragged in 1.12.0 — which `checkDebugAarMetadata` rejected against AGP 8.13
    // with 29 issues. Every other module in this project already takes `compose.*` from the
    // plugin; this one is now the same, and there is one Compose version in the build.
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Encode pipeline (milestone 1) and playback.
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.muxer)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.coroutines.android)
    // Milestone 5: the monthly cap resets on the user's own calendar month, not UTC's.
    implementation(libs.kotlinx.datetime)

    // DI: Koin, not Hilt — Hilt is JVM/Android only and cannot reach Kotlin/Native.
    // ARCHITECTURE.md § 3 allows the swap; recorded in PROJECT.md.
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.koin.compose)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.heifwriter)
    // m4: MP4 creation time, GPS and rotation atoms (STACK.md § Metadata).
    implementation(libs.mp4parser.isoparser)
    // m9: on-device indexing. The bundled models, not the Play-services ones: the
    // downloadable variants fetch over the network and this app has no INTERNET permission.
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.text.recognition)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    debugImplementation(compose.uiTooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// --- Guard 1, merged-manifest half (ARCHITECTURE.md § 14) --------------------
//
// The `trimgallery.guards` plugin already scans this module's own manifests. This adds
// the AGP-merged manifest of every variant, which is where a permission contributed by
// a dependency shows up — the case a source-only scan cannot see.
androidComponents {
    onVariants { variant ->
        val capitalised = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyNoInternetPermissionTask>(
            "verifyNoInternetPermissionMerged$capitalised",
        ) {
            variantName.set(variant.name)
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            // Every variant has a merged manifest. If this task ever scans nothing, the
            // wiring has broken and the guard is passing because it looked at no files.
            requireManifests.set(true)
            report.set(layout.buildDirectory.file("reports/guards/no-internet-${variant.name}.txt"))
        }

        // Fail before anything shippable is produced, not only on `check`.
        //
        // `matching {}.configureEach` rather than `named()`: onVariants runs while AGP is
        // still building the variant model, before it has registered the per-variant
        // lifecycle tasks, so `named("assembleDebug")` throws "Task with name
        // 'assembleDebug' not found in project ':androidApp'" — at configuration time,
        // which took down every job in the build, including the three that never look at
        // Android. This form binds the dependency when the task is realised, which is
        // before its own dependency graph is walked, and never realises anything that
        // would not have run anyway.
        tasks.matching { it.name == "assemble$capitalised" }.configureEach { dependsOn(verify) }
        // ALL_TASK is registered by the guards plugin at apply time, so it exists here.
        tasks.named(TrimGuardsPlugin.ALL_TASK) { dependsOn(verify) }
    }
}
