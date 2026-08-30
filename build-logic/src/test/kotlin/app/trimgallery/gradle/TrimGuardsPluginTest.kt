package app.trimgallery.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end proof that BUILD.md rule 8 is enforced by the build and not just by
 * review: a manifest carrying INTERNET must make the build fail.
 */
class TrimGuardsPluginTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    /** Writes a Kotlin source file into the fixture project. */
    private fun source(relativePath: String, body: String) {
        File(projectDir.root, relativePath).apply {
            parentFile.mkdirs()
            writeText(body)
        }
    }

    private fun writeProject(manifestBody: String) {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                base
                id("trimgallery.guards")
            }
            """.trimIndent(),
        )
        val manifestDir = File(projectDir.root, "src/main").apply { mkdirs() }
        File(manifestDir, "AndroidManifest.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    package=\"app.trimgallery\">\n" +
                manifestBody + "\n" +
                "</manifest>\n",
        )
    }

    private fun run(expectFailure: Boolean) = GradleRunner.create()
        .withProjectDir(projectDir.root)
        .withPluginClasspath()
        .withArguments(TrimGuardsPlugin.NO_INTERNET_TASK, "--stacktrace")
        .run { if (expectFailure) buildAndFail() else build() }

    @Test
    fun `build succeeds when no network permission is declared`() {
        writeProject("""    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""")
        val result = run(expectFailure = false)
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":${TrimGuardsPlugin.NO_INTERNET_TASK}")?.outcome,
        )
    }

    @Test
    fun `build fails when INTERNET is declared`() {
        writeProject("""    <uses-permission android:name="android.permission.INTERNET" />""")
        val result = run(expectFailure = true)
        assertEquals(
            TaskOutcome.FAILED,
            result.task(":${TrimGuardsPlugin.NO_INTERNET_TASK}")?.outcome,
        )
        assertTrue(
            "failure should name the permission:\n${result.output}",
            result.output.contains("android.permission.INTERNET"),
        )
        assertTrue(
            "failure should cite the rule it enforces:\n${result.output}",
            result.output.contains("BUILD.md rule 8"),
        )
    }

    @Test
    fun `check depends on the verification task`() {
        writeProject("""    <uses-permission android:name="android.permission.INTERNET" />""")
        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments("check")
            .buildAndFail()
        assertEquals(
            TaskOutcome.FAILED,
            result.task(":${TrimGuardsPlugin.NO_INTERNET_TASK}")?.outcome,
        )
    }

    // --------------------------------------------------- source boundaries

    @Test
    fun `build fails when a codec is created outside CodecFactory`() {
        writeProject("""    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""")
        source(
            "src/main/kotlin/ThumbnailPipelineAndroid.kt",
            """fun make() = MediaCodec.createEncoderByType("video/hevc")""",
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(TrimGuardsPlugin.BOUNDARIES_TASK)
            .buildAndFail()
        assertEquals(
            TaskOutcome.FAILED,
            result.task(":${TrimGuardsPlugin.BOUNDARIES_TASK}")?.outcome,
        )
        assertTrue(result.output.contains("codecFactory"))
        assertTrue(result.output.contains("MediaCodecFactory.kt"))
    }

    @Test
    fun `build fails when the library is written outside Replacer`() {
        writeProject("""    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""")
        source(
            "src/main/kotlin/MlKitIndexer.kt",
            """fun oops(u: Uri) { DocumentsContract.deleteDocument(resolver, u) }""",
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(TrimGuardsPlugin.BOUNDARIES_TASK)
            .buildAndFail()
        assertTrue(result.output.contains("replacer"))
        assertTrue(result.output.contains("SafeReplacerAndroid.kt"))
    }

    @Test
    fun `build fails when an iOS plist declares a network entitlement`() {
        writeProject("""    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""")
        source("src/main/kotlin/Ok.kt", "val x = 1")
        source(
            "iosApp/TrimGallery.entitlements",
            "<plist version=\"1.0\"><dict><key>com.apple.security.network.client</key><true/></dict></plist>",
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(TrimGuardsPlugin.BOUNDARIES_TASK)
            .buildAndFail()
        assertTrue(result.output.contains("com.apple.security.network.client"))
    }

    @Test
    fun `clean sources pass the boundary guard`() {
        writeProject("""    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""")
        source("src/main/kotlin/MediaCodecFactory.kt", """fun make() = MediaCodec.createEncoderByType("video/hevc")""")
        source("src/main/kotlin/SafeReplacerAndroid.kt", """fun r(u: Uri) { DocumentsContract.renameDocument(res, u, "x") }""")
        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(TrimGuardsPlugin.BOUNDARIES_TASK)
            .build()
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":${TrimGuardsPlugin.BOUNDARIES_TASK}")?.outcome,
        )
    }

    /**
     * `writeProject` creates `src/main` for the manifest, so this fixture *has* a source
     * directory and no Kotlin in it — which is the misconfiguration the rule is for.
     */
    @Test
    fun `a guard that finds nothing to scan fails rather than passing quietly`() {
        writeProject("""    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""")
        // No Kotlin sources at all.
        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(TrimGuardsPlugin.BOUNDARIES_TASK)
            .buildAndFail()
        assertTrue(result.output.contains("found no sources to scan"))
    }

    /**
     * The other side of it: a module with no `src` at all is an empty shell, not a broken
     * one. Seven of the eight `shared/feature` modules are still shells, and
     * `include(":shared:core:model")` creates a container project for every path segment —
     * so this fired on most of the build the first time CI ran the guards.
     */
    @Test
    fun `a module with no sources at all is skipped, and the report says so`() {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                base
                id("trimgallery.guards")
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(TrimGuardsPlugin.BOUNDARIES_TASK)
            .build()
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":${TrimGuardsPlugin.BOUNDARIES_TASK}")?.outcome,
        )
        val report = File(projectDir.root, "build/reports/guards/boundaries.txt").readText()
        assertTrue("report claimed to have scanned something: $report", report.contains("SKIPPED"))
    }

    /**
     * A library module has no hand-written manifest and is not expected to.
     *
     * The guard used to fail on that, which meant every module in this build except the
     * app was reporting its ordinary shape as a misconfiguration — and it only surfaced
     * once CI got far enough to run the guards at all.
     */
    @Test
    fun `a module with no manifest is skipped, and the report says so`() {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                base
                id("trimgallery.guards")
            }
            """.trimIndent(),
        )
        val result = run(expectFailure = false)
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":${TrimGuardsPlugin.NO_INTERNET_TASK}")?.outcome,
        )
        val report = File(projectDir.root, "build/reports/guards/no-internet.txt").readText()
        assertTrue("report claimed to have checked something: $report", report.contains("SKIPPED"))
    }

    /**
     * The other half, and the one that matters: where a manifest *must* exist, an empty
     * scan is still a failure. A guard that passes because it looked at no files is worse
     * than no guard at all.
     */
    @Test
    fun `a module that must have a manifest fails when it has none`() {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        projectDir.newFile("build.gradle.kts").writeText(
            """
            import app.trimgallery.gradle.VerifyNoInternetPermissionTask

            plugins {
                base
                id("trimgallery.guards")
            }

            tasks.named<VerifyNoInternetPermissionTask>("${TrimGuardsPlugin.NO_INTERNET_TASK}") {
                requireManifests.set(true)
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(TrimGuardsPlugin.NO_INTERNET_TASK)
            .buildAndFail()
        assertTrue(result.output.contains("found no manifests to scan"))
    }

    @Test
    fun `verifyGuards runs every guard`() {
        writeProject("""    <uses-permission android:name="android.permission.INTERNET" />""")
        source("src/main/kotlin/Ok.kt", "val x = 1")
        val result = GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(TrimGuardsPlugin.ALL_TASK, "--continue")
            .buildAndFail()
        assertEquals(
            TaskOutcome.FAILED,
            result.task(":${TrimGuardsPlugin.NO_INTERNET_TASK}")?.outcome,
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":${TrimGuardsPlugin.BOUNDARIES_TASK}")?.outcome,
        )
    }
}
