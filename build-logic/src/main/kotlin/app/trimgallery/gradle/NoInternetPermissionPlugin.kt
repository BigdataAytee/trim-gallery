package app.trimgallery.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * Registers `verifyNoInternetPermission`, the build-level enforcement of BUILD.md
 * rule 8 ("No network permission in the manifest").
 *
 * The plugin covers hand-written manifests, which is all it can see on its own. The
 * app module additionally registers one task per variant against the AGP-merged
 * manifest, which is where a permission contributed by a dependency shows up — see
 * `app/build.gradle.kts`. The AGP wiring lives there rather than here so that this
 * plugin, and the scanner it wraps, stay buildable and testable without Google Maven.
 */
class NoInternetPermissionPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val manifests = project.fileTree(project.projectDir).apply {
            include("src/**/AndroidManifest.xml")
            exclude("**/build/**")
        }

        val verify = project.tasks.register<VerifyNoInternetPermissionTask>(TASK_NAME) {
            sourceManifests.from(manifests)
            report.set(project.layout.buildDirectory.file("reports/no-internet/source.txt"))
        }

        project.plugins.withId("base") {
            project.tasks.named<org.gradle.api.Task>("check").configure { dependsOn(verify) }
        }
    }

    companion object {
        const val TASK_NAME = "verifyNoInternetPermission"
    }
}
