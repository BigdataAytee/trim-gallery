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

/**
 * `./gradlew iosCompile` — the Kotlin/Native half of ARCHITECTURE.md § 16.
 *
 * Only exists on a Mac, because that is the only place the iOS targets are declared. Until
 * this ran, "the shared layer is portable" was a claim resting on a source scan: the code had
 * never been through the Kotlin/Native compiler, which is the only thing that can actually
 * answer the question. The portability guard catches the class of error a grep can see;
 * this catches the rest — expect/actual gaps, a dependency with no Native artifact, an API
 * that exists on the JVM standard library and not in kotlin-stdlib-common.
 */
if (System.getProperty("os.name").startsWith("Mac")) {
    tasks.register("iosCompile") {
        group = "verification"
        description = "Compiles every shared module for both iOS targets."
        dependsOn(
            subprojects
                .filter { it.path.startsWith(":shared:") }
                .flatMap {
                    listOf(
                        "${it.path}:compileKotlinIosArm64",
                        "${it.path}:compileKotlinIosSimulatorArm64",
                    )
                },
        )
    }
}

tasks.register("guards") {
    group = "verification"
    description = "Runs every build guard in every module (ARCHITECTURE.md § 14)."
    dependsOn(subprojects.map { "${it.path}:verifyGuards" })
}
