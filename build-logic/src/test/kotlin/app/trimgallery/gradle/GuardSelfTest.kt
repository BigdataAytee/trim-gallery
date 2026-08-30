package app.trimgallery.gradle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The guards, tested against deliberate violations.
 *
 * Every other test in this package checks that a guard behaves correctly on a case somebody
 * thought of. This one checks something weaker and more important: **that each guard fires
 * at all, in every language it claims to police.**
 *
 * The distinction is not academic. From milestone 4 to milestone 15 the codec and replacer
 * rules carried patterns for `VTCompressionSessionCreate` and `PHAssetChangeRequest` — and
 * the harness globbed only `.kt`, so those patterns had never been run against a single line
 * of Swift. They were not guards. They were comments that happened to be written as regular
 * expressions, and nothing in the build could tell the difference.
 *
 * So the rule here is: a guard with no planted violation in a language it claims to cover
 * **fails this test**. [everyRuleHasAProbeInEveryLanguageItClaims] is the one that enforces
 * it, and it is the reason a new rule cannot be added quietly.
 */
class GuardSelfTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * One deliberate violation, and the clean file it is derived from.
     *
     * [clean] is not decoration: a probe that fires on both is testing nothing, because a
     * rule that matches everything passes the firing half of this suite while failing at its
     * actual job.
     */
    private data class Probe(
        val ruleId: String,
        val language: String,
        val path: String,
        val violation: String,
        val clean: String,
    )

    private val probes = listOf(
        // ---------------------------------------------------------- codecFactory
        Probe(
            ruleId = "codecFactory",
            language = "kt",
            path = "androidApp/src/main/kotlin/ThumbnailHelper.kt",
            violation = """
                package app.trimgallery
                val encoder = MediaCodec.createEncoderByType("video/hevc")
            """.trimIndent(),
            clean = """
                package app.trimgallery
                val encoder = codecFactory.encoder(spec, background = true)
            """.trimIndent(),
        ),
        Probe(
            ruleId = "codecFactory",
            language = "swift",
            path = "iosApp/TrimGallery/engine/ThumbnailHelper.swift",
            violation = """
                import VideoToolbox
                VTCompressionSessionCreate(allocator: nil, width: 1920, height: 1080)
            """.trimIndent(),
            clean = """
                import VideoToolbox
                let encoder = factory.encoder(spec: spec, background: true)
            """.trimIndent(),
        ),

        // -------------------------------------------------------------- replacer
        Probe(
            ruleId = "replacer",
            language = "kt",
            path = "androidApp/src/main/kotlin/MlKitIndexer.kt",
            violation = """
                package app.trimgallery
                DocumentsContract.renameDocument(resolver, uri, "new.mp4")
            """.trimIndent(),
            clean = """
                package app.trimgallery
                val source = storage.openRead(item.platformRef)
            """.trimIndent(),
        ),
        Probe(
            ruleId = "replacer",
            language = "swift",
            path = "iosApp/TrimGallery/ui/GalleryView.swift",
            violation = """
                import Photos
                PHAssetChangeRequest.deleteAssets(assets as NSArray)
            """.trimIndent(),
            clean = """
                import Photos
                let assets = PHAsset.fetchAssets(with: options)
            """.trimIndent(),
        ),

        // ------------------------------------------------------------- noNetwork
        Probe(
            ruleId = "noNetwork",
            language = "kt",
            path = "shared/core/data/src/androidMain/kotlin/Uploader.kt",
            violation = """
                package app.trimgallery
                import java.net.HttpURLConnection
            """.trimIndent(),
            clean = """
                package app.trimgallery
                import android.net.Uri
            """.trimIndent(),
        ),
        Probe(
            ruleId = "noNetwork",
            language = "swift",
            path = "iosApp/TrimGallery/engine/Uploader.swift",
            violation = """
                import Foundation
                let task = URLSession.shared.dataTask(with: request)
            """.trimIndent(),
            clean = """
                import Foundation
                let url = URL(fileURLWithPath: plan.content.path)
            """.trimIndent(),
        ),

        // ------------------------------------------------------ readOnlyOriginals
        Probe(
            ruleId = "readOnlyOriginals",
            language = "kt",
            path = "androidApp/src/main/kotlin/MetadataFixer.kt",
            violation = """
                package app.trimgallery
                val fd = resolver.openFileDescriptor(original, "rw")
            """.trimIndent(),
            clean = """
                package app.trimgallery
                val fd = resolver.openFileDescriptor(original, "r")
            """.trimIndent(),
        ),

        // -------------------------------------------------------- portableCommon
        Probe(
            ruleId = "portableCommon",
            language = "kt",
            path = "shared/core/domain/src/commonMain/kotlin/Clock.kt",
            violation = """
                package app.trimgallery
                import java.time.Instant
            """.trimIndent(),
            clean = """
                package app.trimgallery
                import kotlinx.datetime.Instant
            """.trimIndent(),
        ),
    )

    private fun write(path: String, body: String): File =
        File(tmp.root, path).apply { parentFile.mkdirs(); writeText(body) }

    // ------------------------------------------------------------------- firing

    @Test
    fun `every planted violation is caught by the rule that claims it`() {
        for (probe in probes) {
            val file = write("fire/${probe.ruleId}/${probe.language}/${probe.path}", probe.violation)
            val violations = SourceBoundaryScanner.scan(file)
            assertTrue(
                "${probe.ruleId} (${probe.language}) did not fire on:\n${probe.violation}",
                violations.any { it.rule.id == probe.ruleId },
            )
        }
    }

    /**
     * A rule that matches everything passes the firing half of this suite while failing at
     * its actual job, so each probe carries the honest version of the same file.
     */
    @Test
    fun `no clean counterpart is caught by anything`() {
        for (probe in probes) {
            val file = write("clean/${probe.ruleId}/${probe.language}/${probe.path}", probe.clean)
            val violations = SourceBoundaryScanner.scan(file)
            assertEquals(
                "${probe.ruleId} (${probe.language}) fired on clean source:\n${probe.clean}\n$violations",
                emptyList<SourceBoundaryScanner.Violation>(),
                violations,
            )
        }
    }

    // -------------------------------------------------------------- the meta-test

    /**
     * The one that makes the rest binding.
     *
     * Adding a rule to `DEFAULT_RULES`, or widening an existing rule's `languages`, without
     * planting a violation for it fails here. That is deliberate: between milestones 4 and
     * 15 this project shipped two rules whose iOS patterns had never been run against any
     * Swift, and nothing in the build could tell.
     */
    @Test
    fun `every rule has a probe in every language it claims`() {
        val covered = probes.map { it.ruleId to it.language }.toSet()
        val missing = SourceBoundaryScanner.DEFAULT_RULES.flatMap { rule ->
            rule.languages.filterNot { language -> (rule.id to language) in covered }
                .map { "${rule.id} (.$it)" }
        }
        assertEquals("rules with no planted violation: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `every probe names a rule that actually exists`() {
        val known = SourceBoundaryScanner.DEFAULT_RULES.map { it.id }.toSet()
        val unknown = probes.map { it.ruleId }.distinct().filterNot { it in known }
        assertEquals("probes for rules that do not exist: $unknown", emptyList<String>(), unknown)
    }

    // ------------------------------------------------ the other two scanners

    /**
     * The manifest guard, which is not a source rule and so cannot be covered by a probe
     * above — but is the one BUILD.md rule 8 rests on, so it gets the same treatment.
     */
    @Test
    fun `the manifest guard fires on a planted permission and not on a clean manifest`() {
        val bad = write(
            "manifest/bad/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
            </manifest>
            """.trimIndent(),
        )
        assertEquals(1, ManifestPermissionScanner.scan(bad).size)

        val good = write(
            "manifest/good/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
            </manifest>
            """.trimIndent(),
        )
        assertTrue(ManifestPermissionScanner.scan(good).isEmpty())
    }

    @Test
    fun `every forbidden permission is one the manifest guard actually catches`() {
        for (permission in ManifestPermissionScanner.FORBIDDEN_PERMISSIONS) {
            val file = write(
                "manifest/${permission.substringAfterLast('.')}/AndroidManifest.xml",
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <uses-permission android:name="$permission" />
                </manifest>
                """.trimIndent(),
            )
            assertEquals(permission, 1, ManifestPermissionScanner.scan(file).size)
        }
    }

    /** The iOS side of the same rule: entitlements and Info.plist. */
    @Test
    fun `the plist guard fires on a planted key and not on a clean plist`() {
        val bad = write(
            "plist/bad/Info.plist",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
                <key>NSAppTransportSecurity</key><dict/>
            </dict></plist>
            """.trimIndent(),
        )
        assertEquals(1, IosNetworkScanner.scan(bad).size)

        val good = write(
            "plist/good/Info.plist",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
                <key>CFBundleName</key><string>Trim Gallery</string>
            </dict></plist>
            """.trimIndent(),
        )
        assertTrue(IosNetworkScanner.scan(good).isEmpty())
    }

    @Test
    fun `every forbidden plist key is one the guard actually catches`() {
        for (key in IosNetworkScanner.FORBIDDEN_KEYS) {
            val file = write(
                "plist/$key/Info.plist",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0"><dict><key>$key</key><true/></dict></plist>
                """.trimIndent(),
            )
            assertEquals(key, 1, IosNetworkScanner.scan(file).size)
        }
    }
}
