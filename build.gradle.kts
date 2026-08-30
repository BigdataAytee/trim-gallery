plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    id("trimgallery.guards") apply false
}

subprojects {
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)

    // ARCHITECTURE.md § 14. Applied per module so each scans its own sources; the
    // build-logic included build and the parked design/ prototype are not Gradle
    // subprojects and are therefore never scanned.
    apply(plugin = "trimgallery.guards")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }
}

// `./gradlew sharedTest` — the JVM half of ARCHITECTURE.md § 14, runnable without an
// Android SDK or a Mac. This is what CI gates on.
tasks.register("sharedTest") {
    group = "verification"
    description = "Runs the shared JVM unit tests for every shared module."
    dependsOn(
        subprojects
            .filter { it.path.startsWith(":shared:") }
            .map { "${it.path}:jvmTest" },
    )
}

tasks.register("guards") {
    group = "verification"
    description = "Runs every build guard in every module (ARCHITECTURE.md § 14)."
    dependsOn(subprojects.map { "${it.path}:verifyGuards" })
}
