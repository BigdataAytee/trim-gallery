import app.trimgallery.gradle.VerifyNoInternetPermissionTask
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)

    // BUILD.md rule 8, enforced by the build. Registers `verifyNoInternetPermission`
    // over this module's own manifests; the per-variant merged-manifest tasks are
    // registered below.
    id("app.trimgallery.no-internet")
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

        // BUILD.md / STACK.md: arm64-v8a only. The native metric libraries are built
        // for one ABI, and a second ABI would ship code that cannot run them.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Nothing in this app talks to the network, so a call that needs INTERNET is a
        // bug even before the manifest check catches the permission.
        error += listOf("MissingPermission")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Milestone 1: Media3 Transformer encode + ExoPlayer playback of the result.
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.muxer)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
}

// --- BUILD.md rule 8: no network permission in the manifest -------------------
//
// Wired per variant against the AGP-merged manifest, so a permission contributed by
// a dependency is caught as well as one written by us. The scanning logic lives in
// build-logic and is unit tested there.
androidComponents {
    onVariants { variant ->
        val verify = tasks.register<VerifyNoInternetPermissionTask>(
            "verifyNoInternetPermission${variant.name.replaceFirstChar { it.uppercase() }}",
        ) {
            variantName.set(variant.name)
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            sourceManifests.from(android.sourceSets.getByName("main").manifest.srcFile)
            report.set(layout.buildDirectory.file("reports/no-internet/${variant.name}.txt"))
        }

        // Fail before anything shippable is produced, not only on `check`.
        tasks.named("assemble${variant.name.replaceFirstChar { it.uppercase() }}") {
            dependsOn(verify)
        }
        tasks.named("check") {
            dependsOn(verify)
        }
    }
}
