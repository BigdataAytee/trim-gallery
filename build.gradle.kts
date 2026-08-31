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

    // Generated code is not ours to format. SQLDelight adds its generated interface to the
    // module's `commonMain` Kotlin source set, so `ktlintCommonMainSourceSetCheck` lints
    // files nobody wrote and nobody can fix — and it failed the build on them the first time
    // CI got far enough to run code generation before linting.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter {
            val generated = "${File.separator}build${File.separator}"
            exclude { element -> element.file.path.contains(generated) }
        }
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        // One baseline for the whole build: detekt matches on rule and signature, and a
        // signature names the file, not the module. See the file's own header for what it
        // is allowed to contain.
        baseline = rootProject.file("config/detekt/baseline.xml")

        // Detekt's default source set is `src/main/kotlin` and `src/test/kotlin`, which no
        // Kotlin Multiplatform module has: theirs are `src/commonMain/kotlin`,
        // `src/androidMain/kotlin` and so on. Every `:shared:*:detekt` task was therefore
        // NO-SOURCE — static analysis configured since milestone 2 and analysing nothing but
        // the Android app. This points it at whatever source directories the module has,
        // KMP or not.
        source.setFrom(
            project.file("src").listFiles().orEmpty()
                .map { File(it, "kotlin") }
                .filter { it.isDirectory }
                .sorted(),
        )
    }
}

/**
 * The shared modules that are actually modules.
 *
 * `:shared`, `:shared:core` and `:shared:feature` exist only as path segments — `include`
 * creates a project for every intermediate segment — and they have no build file, no Kotlin
 * plugin and therefore no `jvmTest`. Filtering on the path prefix alone asked for
 * `:shared:core:jvmTest`, which failed the whole build with "task not found" before a
 * single test ran.
 */
val sharedModules: List<Project>
    get() = subprojects.filter { it.path.startsWith(":shared:") && it.buildFile.exists() }

// `./gradlew sharedTest` — the JVM half of ARCHITECTURE.md § 14, runnable without an
// Android SDK or a Mac. This is what CI gates on.
tasks.register("sharedTest") {
    group = "verification"
    description = "Runs the shared JVM unit tests for every shared module."
    dependsOn(sharedModules.map { "${it.path}:jvmTest" })
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
            sharedModules.flatMap {
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
