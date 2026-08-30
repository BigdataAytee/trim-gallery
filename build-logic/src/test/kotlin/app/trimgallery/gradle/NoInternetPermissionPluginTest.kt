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
class NoInternetPermissionPluginTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private fun writeProject(manifestBody: String) {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                base
                id("app.trimgallery.no-internet")
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
        .withArguments(NoInternetPermissionPlugin.TASK_NAME, "--stacktrace")
        .run { if (expectFailure) buildAndFail() else build() }

    @Test
    fun `build succeeds when no network permission is declared`() {
        writeProject("""    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />""")
        val result = run(expectFailure = false)
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":${NoInternetPermissionPlugin.TASK_NAME}")?.outcome,
        )
    }

    @Test
    fun `build fails when INTERNET is declared`() {
        writeProject("""    <uses-permission android:name="android.permission.INTERNET" />""")
        val result = run(expectFailure = true)
        assertEquals(
            TaskOutcome.FAILED,
            result.task(":${NoInternetPermissionPlugin.TASK_NAME}")?.outcome,
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
            result.task(":${NoInternetPermissionPlugin.TASK_NAME}")?.outcome,
        )
    }
}
