plugins {
    `kotlin-dsl`
}

// Deliberately depends on nothing but the Gradle API and the Kotlin stdlib.
//
// The Android Gradle Plugin is NOT on this classpath: the manifest scanner has to
// stay buildable and testable without Google Maven, and the AGP-specific wiring
// (hooking the merged manifest of each variant) lives in `app/build.gradle.kts`
// where AGP is already applied.
gradlePlugin {
    plugins {
        create("trimGuards") {
            id = "trimgallery.guards"
            implementationClass = "app.trimgallery.gradle.TrimGuardsPlugin"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(gradleTestKit())
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
