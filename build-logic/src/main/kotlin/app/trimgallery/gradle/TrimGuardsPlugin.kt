package app.trimgallery.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * Registers Trim Gallery's three build guards (ARCHITECTURE.md § 14):
 *
 *  1. `verifyNoInternetPermission` — no network permission in any Android manifest.
 *  2. `verifySourceBoundaries` — codecs only in `CodecFactory`, library writes only in
 *     `Replacer`, and no networking API anywhere (plus iOS plist/entitlement keys).
 *  3. both are wired into `check`, and the app module additionally runs (1) against the
 *     AGP-merged manifest of every variant.
 *
 * The plugin depends on nothing but the Gradle API. The Android Gradle Plugin is
 * deliberately off this classpath: the guards have to run in CI before anything Android
 * resolves, and they have to be unit testable without an SDK. The merged-manifest
 * wiring lives in `androidApp/build.gradle.kts`, where AGP is already applied.
 */
class TrimGuardsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val manifests = project.fileTree(project.projectDir).apply {
            include("**/AndroidManifest.xml")
            exclude("**/build/**")
        }

        val sources = project.fileTree(project.projectDir).apply {
            include("**/*.kt", "**/*.java", "**/*.swift")
            exclude("**/build/**", "**/*.gradle.kts")
        }

        val plists = project.fileTree(project.projectDir).apply {
            include("**/Info.plist", "**/*.entitlements")
            exclude("**/build/**")
        }

        val noInternet = project.tasks.register<VerifyNoInternetPermissionTask>(NO_INTERNET_TASK) {
            sourceManifests.from(manifests)
            report.set(project.layout.buildDirectory.file("reports/guards/no-internet.txt"))
        }

        val boundaries = project.tasks.register<VerifySourceBoundariesTask>(BOUNDARIES_TASK) {
            this.sources.from(sources)
            this.plists.from(plists)
            report.set(project.layout.buildDirectory.file("reports/guards/boundaries.txt"))
        }

        // One task to run them all, for CI and for humans.
        val all = project.tasks.register(ALL_TASK) {
            group = "verification"
            description = "Runs every Trim Gallery build guard (ARCHITECTURE.md § 14)."
            dependsOn(noInternet, boundaries)
        }

        project.plugins.withId("base") {
            project.tasks.named<org.gradle.api.Task>("check").configure { dependsOn(all) }
        }
    }

    companion object {
        const val NO_INTERNET_TASK = "verifyNoInternetPermission"
        const val BOUNDARIES_TASK = "verifySourceBoundaries"
        const val ALL_TASK = "verifyGuards"
    }
}
