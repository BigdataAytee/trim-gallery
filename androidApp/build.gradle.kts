import app.trimgallery.gradle.TrimGuardsPlugin
import app.trimgallery.gradle.VerifyNoInternetPermissionTask
import com.android.build.api.artifact.SingleArtifact

// ARCHITECTURE.md § 3 — androidApp. Platform engines, storage, scheduler and the host
// Activity. Everything else lives in shared/.

// Set only by the CI smoke job, which runs on a hosted x86_64 emulator. Everything else —
// a developer's build, a field tester's phone on USB — leaves it unset and gets arm64-v8a
// alone. See the `smoke` build type below.
//
// `val`, not `const val`: a .kts script's top level is the body of an implicit class, and
// `const` is only legal on a real top level or in an object. `const val` here is a script
// compilation error, which fails *configuration* — so every job in the workflow goes red,
// including the ones that touch nothing Android.
val smokeX86Property = "trimgallery.smoke.x86_64"

plugins {
    // No `kotlin.android` here: AGP 9 has built-in Kotlin support and *rejects* the
    // `org.jetbrains.kotlin.android` plugin outright ("no longer required for Kotlin
    // support since AGP 9.0"). The Kotlin version still comes from the `kotlin` line in
    // the catalogue, via the compiler AGP pulls in.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * Seven characters, which is what GitHub shows and what a person can read back over a
 * message.
 *
 * A plain `val`, not `const val`: a .kts script's top level is the body of an implicit
 * class, and `const` is only legal on a real top level or in an object — the same trap the
 * `smokeX86Property` comment records, and it fails *configuration*, so every job in the
 * workflow goes red including the ones that never touch Android.
 *
 * Both of these sit **below** `plugins { }` rather than beside `smokeX86Property` above it.
 * Statements before that block are compiled in a restricted scope with no Project API, so
 * `providers` does not exist there; `smokeX86Property` gets away with it by being a string
 * literal.
 */
val shortShaLength = 7

/**
 * The commit this APK was built from, for "which build am I on?".
 *
 * A field report that cannot name its build is a report about an unknown program: the
 * first question after "it crashed" is whether the fix for the last crash was even in what
 * the tester installed, and without this the only way to answer it is to compare file
 * sizes on a release page.
 *
 * `GITHUB_SHA` first, because CI has it for free and knows the exact commit it checked
 * out; `git` second, for a developer's own build. **Neither may fail the build.** A
 * version string is not worth a red pipeline, so the whole thing is wrapped and falls back
 * to "unknown" — a build that says it does not know which commit it is, is still more
 * honest than one that cannot be built.
 */
val gitSha: String = run {
    val fromCi = System.getenv("GITHUB_SHA")?.trim()?.take(shortShaLength)
    if (!fromCi.isNullOrEmpty()) {
        return@run fromCi
    }
    runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=$shortShaLength", "HEAD")
            // A checkout with no git directory — an unpacked source archive — must not
            // fail configuration, which in this build fails every job including the ones
            // that never touch Android.
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"
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

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        // arm64-v8a only (ARCHITECTURE.md § 10). The native metric libraries are built
        // for one ABI; a second would ship code that cannot run them.
        //
        // Expressed here and *not* also as an `abi` split. AGP rejects both at once
        // ("'arm64-v8a' in ndk abiFilters cannot be present when splits abi filters are
        // set"), and it rejects it during configuration, so the conflict failed every job
        // in the build — including the ones that never touch Android. Splits exist to
        // produce one APK per ABI; with a single ABI there is nothing to split, and
        // `abiFilters` is the app module's own ABI declaration. The shared library
        // modules no longer carry one: AGP 9's KMP library plugin has no `ndk` block.
        // They ship no native code, so the ABI set is decided here and verified by the
        // afterEvaluate assertion below and by tools/check-apk-libraries.sh.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The metric libraries (ARCHITECTURE.md § 10). arm64 only, NEON on — the metrics
        // are the bottleneck and a scalar build is not worth shipping.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                // The SIMD flag is NOT set here any more. Gradle applies cFlags to every
                // ABI, so `-march=armv8-a+simd` reached the x86_64 compiler the moment the
                // smoke variant added that ABI, and clang rejects an ARM architecture name
                // outright. CMake knows which ABI it is configuring; it sets the flag now.
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

    // AGP builds an androidTest component for exactly one build type, and it defaults to
    // `debug` — so with this left alone there is no `smokeAndroidTest`, and therefore no
    // `pixelSmokeAndroidTest` for the managed device to run:
    //
    //     Cannot locate tasks that match ':androidApp:pixelSmokeAndroidTest'
    //
    // Pointing it at `smoke` is what makes the instrumented tests build against the variant
    // that has an ABI the emulator can run. `smoke` is `initWith(debug)`, so the existing
    // instrumented tests are unaffected by the move.
    testBuildType = "smoke"

    testOptions {
        managedDevices {
            localDevices {
                create("pixel") {
                    device = "Pixel 6"
                    apiLevel = 34
                    // ATD: no Play services, no store, and a much smaller image — this test
                    // starts an activity, it does not need a Google account. Paired with the
                    // `smoke` build type above, the image is x86_64 and runs under KVM at
                    // native speed rather than being emulated instruction by instruction.
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }

    buildTypes {
        // Debug and release ship arm64-v8a alone, from `defaultConfig.ndk.abiFilters`.
        //
        // `smoke` exists so the app can be launched on an emulator in CI and nothing else.
        // No GitHub-hosted runner can virtualise an arm64 Android image — macOS runners are
        // themselves VMs and refuse with `HVF error: HV_UNSUPPORTED` — so the only way to
        // prove the app starts is an x86_64 emulator, which needs an x86_64 build of the
        // native libraries. `abiFilters` unions with defaultConfig's, so this variant is
        // arm64-v8a + x86_64 and every other variant is unchanged. It is never published:
        // `release` is what ships, and it has one ABI.
        create("smoke") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            // x86_64 is opt-in, and only the hosted emulator opts in.
            //
            // Both `pixelSmokeAndroidTest` (managed device) and `connectedSmokeAndroidTest`
            // (a real phone on USB) build this one variant, so an unconditional second ABI
            // made every physical run cross-compile the whole native tree twice — libjxl,
            // jpegli, libvmaf and oxipng — for an architecture that device cannot execute.
            // The property is set by the CI smoke job and nowhere else, so a developer or a
            // field tester with a device attached builds arm64-v8a alone, which is also
            // what ships.
            if (providers.gradleProperty(smokeX86Property).orNull == "true") {
                ndk { abiFilters += "x86_64" }
            }
            // Set rather than inherited, and asserted below.
            //
            // `initWith` copies the debug build type's properties, and an instrumented test
            // needs two of them to be true: the variant must be debuggable (the test runner
            // attaches to the process) and it must be signed (an unsigned APK does not
            // install, and the emulator reports that as a install failure rather than as a
            // configuration mistake). The `benchmark` type below already re-states its
            // signing config despite its own `initWith`, so this build has never relied on
            // that inheritance — being explicit here says what the variant needs instead of
            // depending on what another build type happens to carry.
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }

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

    // `buildConfig` is off by default in AGP 9 and off again in gradle.properties; this
    // module needs exactly one generated constant, the commit above.
    buildFeatures {
        compose = true
        buildConfig = true
    }

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
    implementation(libs.compose.ui)
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

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    // The runner itself, not just the JUnit4 rules. `testInstrumentationRunner` above has
    // named `androidx.test.runner.AndroidJUnitRunner` since milestone 1, but nothing put
    // that class on the classpath — `androidx.test.ext:junit` pulls core and monitor only.
    // Invisible until an instrumented test actually ran, which first happened today.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // The Compose test rule, for the screen journeys in GalleryJourneyTest. Until these
    // existed the only thing any instrumented test asserted was that the Activity reached
    // RESUMED — which it did on a build whose first screen crashed the moment a folder was
    // granted, because reaching RESUMED says the process came up and nothing more.
    androidTestImplementation(libs.compose.ui.test.junit4)
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

// The smoke variant's two load-bearing properties, checked rather than assumed.
//
// `smoke` exists only so an instrumented test can install and drive the app on an emulator,
// and that needs it debuggable (the test runner attaches to the process) and signed (an
// unsigned APK will not install, and the failure surfaces as INSTALL_PARSE_FAILED rather
// than as "somebody changed a build type"). Both come from `initWith(debug)` plus the two
// explicit lines above; this fails configuration if a future edit removes either, because
// the alternative is a red emulator job whose message points nowhere near the cause.
//
// It also pins the ABI set, which is the other thing the emulator depends on: x86_64 must be
// present or the APK cannot install on a hosted runner, and arm64-v8a must stay so the
// variant still resembles what ships.
afterEvaluate {
    val smoke = android.buildTypes.getByName("smoke")
    check(smoke.isDebuggable) {
        "The `smoke` build type must be debuggable: the instrumented test runner attaches to " +
            "the app process, and a non-debuggable build refuses that."
    }
    checkNotNull(smoke.signingConfig) {
        "The `smoke` build type must be signed. An unsigned APK does not install, and the " +
            "emulator reports it as a parse failure rather than as a build-type mistake."
    }
    val abis = android.defaultConfig.ndk.abiFilters + smoke.ndk.abiFilters
    check("arm64-v8a" in abis) {
        "The `smoke` build type must carry arm64-v8a; got $abis. It is the ABI that ships, " +
            "so a smoke run that does not include it is not testing the real artefact."
    }
    // The second ABI is asserted only when it was asked for. Checking it unconditionally
    // would fail every physical-device run, which is exactly the case this change exists
    // to keep cheap.
    if (providers.gradleProperty(smokeX86Property).orNull == "true") {
        check("x86_64" in abis) {
            "$smokeX86Property is set, so the `smoke` build type must carry x86_64; got " +
                "$abis. Without it the APK cannot install on a hosted emulator, which is " +
                "the only kind of Android device CI can virtualise."
        }
    }
}
