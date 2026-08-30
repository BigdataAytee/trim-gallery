package app.trimgallery.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if a forbidden network permission reaches the manifest.
 *
 * Wired against the *merged* manifest, not just our own sources, so a permission
 * contributed by a dependency's manifest is caught too — that is the case a
 * source-only grep would miss, and the likelier one in practice.
 */
@CacheableTask
abstract class VerifyNoInternetPermissionTask : DefaultTask() {

    /** The AGP-merged manifest for one variant. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    /** Hand-written manifests, checked as well so failures point at the real source. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceManifests: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    /** Name of the variant, used only in log output. */
    @get:org.gradle.api.tasks.Input
    @get:Optional
    abstract val variantName: Property<String>

    init {
        group = "verification"
        description = "Fails the build if the manifest declares a network permission (BUILD.md rule 8)."
    }

    @TaskAction
    fun verify() {
        val manifests = buildList {
            addAll(sourceManifests.files)
            if (mergedManifest.isPresent) add(mergedManifest.get().asFile)
        }

        val violations = ManifestPermissionScanner.scanAll(manifests)

        val reportFile = report.get().asFile
        reportFile.parentFile?.mkdirs()

        if (violations.isNotEmpty()) {
            reportFile.writeText(ManifestPermissionScanner.failureMessage(violations))
            throw GradleException(ManifestPermissionScanner.failureMessage(violations))
        }

        val checked = manifests.filter { it.isFile }
        reportFile.writeText(
            buildString {
                appendLine("OK — no forbidden network permission.")
                appendLine("variant: ${variantName.getOrElse("(none)")}")
                appendLine("forbidden: ${ManifestPermissionScanner.FORBIDDEN_PERMISSIONS.sorted().joinToString(", ")}")
                appendLine("manifests checked (${checked.size}):")
                checked.forEach { appendLine("  ${it.path}") }
            },
        )

        if (checked.isEmpty()) {
            // Nothing to check means the wiring is wrong, and a check that silently
            // passes because it looked at no files is worse than no check at all.
            throw GradleException(
                "verifyNoInternetPermission found no manifests to scan. The task is " +
                    "misconfigured — it must be wired to the merged manifest of each variant.",
            )
        }

        logger.lifecycle("No network permission in ${checked.size} manifest(s) — BUILD.md rule 8 holds.")
    }
}
