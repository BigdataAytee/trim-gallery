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

    /**
     * Whether scanning nothing is a misconfiguration.
     *
     * True where a manifest must exist: the application module's own sources, and the
     * AGP-merged manifest of every variant. There, an empty scan means the wiring broke and
     * the guard is passing because it looked at no files — worse than no guard at all.
     *
     * False for a library module, which legitimately has none. Only `androidApp` has a
     * hand-written `AndroidManifest.xml`; every other module in this build gets one
     * synthesised by AGP, and failing them all was this guard reporting the repository's
     * ordinary shape as a fault.
     */
    @get:org.gradle.api.tasks.Input
    abstract val requireManifests: Property<Boolean>

    init {
        group = "verification"
        description = "Fails the build if the manifest declares a network permission (BUILD.md rule 8)."
        // Off unless a caller asks for it: most modules have no manifest and are not
        // expected to. The two places that must have one say so explicitly.
        requireManifests.convention(false)
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
        val mustHaveOne = requireManifests.getOrElse(false)
        reportFile.writeText(
            buildString {
                // Says what it did, not what it hoped. A report that reads "OK" after
                // scanning nothing is how a dead guard survives a code review.
                appendLine(
                    if (checked.isEmpty()) {
                        "SKIPPED — this module has no manifest to scan."
                    } else {
                        "OK — no forbidden network permission."
                    },
                )
                appendLine("variant: ${variantName.getOrElse("(none)")}")
                appendLine("manifest required here: $mustHaveOne")
                appendLine("forbidden: ${ManifestPermissionScanner.FORBIDDEN_PERMISSIONS.sorted().joinToString(", ")}")
                appendLine("manifests checked (${checked.size}):")
                checked.forEach { appendLine("  ${it.path}") }
            },
        )

        if (checked.isEmpty()) {
            if (mustHaveOne) {
                // Nothing to check means the wiring is wrong, and a check that silently
                // passes because it looked at no files is worse than no check at all.
                throw GradleException(
                    "$path found no manifests to scan. " +
                        "The task is misconfigured — it must be wired to the merged manifest of " +
                        "each variant, and to the application module's own sources.",
                )
            }
            logger.lifecycle("No manifest in this module — nothing for BUILD.md rule 8 to check here.")
            return
        }

        logger.lifecycle("No network permission in ${checked.size} manifest(s) — BUILD.md rule 8 holds.")
    }
}
