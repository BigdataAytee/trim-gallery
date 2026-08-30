package app.trimgallery.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a restricted API is called outside the one class allowed to own
 * it — the codec and library-write guards from ARCHITECTURE.md § 14, plus the
 * source half of the no-network guard.
 */
@CacheableTask
abstract class VerifySourceBoundariesTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** iOS `Info.plist` and `.entitlements` files, if the target is present. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val plists: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    /**
     * Whether scanning nothing is a misconfiguration.
     *
     * The plugin sets this from whether the project has a `src` directory at all, which is
     * the honest form of the rule: if there are sources on disk and this task found none,
     * the wiring is broken and the guard is passing because it looked at nothing. If the
     * module has no sources — seven of the eight `shared/feature` modules are still empty
     * shells, and `include(":shared:core:model")` creates container projects with nothing in
     * them — then there is nothing here to guard, and failing is the guard reporting the
     * repository's ordinary shape as a fault.
     */
    @get:org.gradle.api.tasks.Input
    abstract val requireSources: Property<Boolean>

    init {
        group = "verification"
        description = "Fails the build if codecs are created outside CodecFactory, the library " +
            "is written outside Replacer, or any networking API is used (ARCHITECTURE.md § 14)."
        requireSources.convention(false)
    }

    @TaskAction
    fun verify() {
        val sourceFiles = sources.files.filter { it.isFile }
        val plistFiles = plists.files.filter { it.isFile }

        val boundaryViolations = SourceBoundaryScanner.scanAll(sourceFiles)
        val plistViolations = IosNetworkScanner.scanAll(plistFiles)

        val reportFile = report.get().asFile
        reportFile.parentFile?.mkdirs()

        if (boundaryViolations.isNotEmpty() || plistViolations.isNotEmpty()) {
            val message = buildString {
                if (boundaryViolations.isNotEmpty()) {
                    append(SourceBoundaryScanner.failureMessage(boundaryViolations))
                }
                if (plistViolations.isNotEmpty()) {
                    appendLine()
                    append(IosNetworkScanner.failureMessage(plistViolations))
                }
            }
            reportFile.writeText(message)
            throw GradleException(message)
        }

        if (sourceFiles.isEmpty()) {
            if (requireSources.getOrElse(false)) {
                // A guard that passes because it looked at nothing is worse than no guard.
                throw GradleException(
                    "$path found no sources to scan, but this project has a src directory. " +
                        "The task is misconfigured — it must be pointed at the project's " +
                        "Kotlin/Swift sources.",
                )
            }
            reportFile.writeText(
                buildString {
                    appendLine("SKIPPED — this module has no sources to scan.")
                    appendLine("plists scanned: ${plistFiles.size}")
                },
            )
            logger.lifecycle("No sources in this module — nothing for the boundary guard to check here.")
            return
        }

        reportFile.writeText(
            buildString {
                appendLine("OK — architectural boundaries hold.")
                appendLine("rules: ${SourceBoundaryScanner.DEFAULT_RULES.joinToString(", ") { it.id }}")
                appendLine("source files scanned: ${sourceFiles.size}")
                appendLine("plists scanned: ${plistFiles.size}")
            },
        )

        logger.lifecycle(
            "Boundaries hold across ${sourceFiles.size} source file(s): codecs only in " +
                "CodecFactory, library writes only in Replacer, originals opened read-only, " +
                "no networking API.",
        )
    }
}
