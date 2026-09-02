package app.trimgallery.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.StartupGuard
import app.trimgallery.feature.settings.SettingsTestTags
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext

/**
 * Settings, and the two things on it that are load-bearing.
 *
 * Most of this screen is preference. Two parts are not: how long an original is kept before
 * "Free the space" deletes it for good, and which build the user is running — the question
 * every field report has turned on so far, and the one this project answered wrongly for
 * three builds because nothing on screen said.
 */
@RunWith(AndroidJUnit4::class)
class SettingsJourneyTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private val compose = createAndroidComposeRule<MainActivity>()

    private val graph = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                StartupGuard(app).clear()
                CrashReports(app).clear()
                GlobalContext.get().apply {
                    declare<GrantedFolders>(FakeGrantedFolders(app, listOf(journeyGrant())))
                    declare<LibraryStorage>(FakeLibrary(listOf(journeyPhoto(app), journeyVideo(app))))
                    declare<TrimRepository>(inMemoryRepository(app))
                }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(graph).around(compose)

    /**
     * The retention control is on the screen and moves.
     *
     * It decides how long an original survives in the bin under "Free the space", which is
     * the only mode that ever loses a file for good. A stepper that renders but does not
     * change the stored value would be the worst kind of working-looking control.
     */
    @Test
    fun keepOriginalsForCanBeChanged() {
        openSettings()
        awaitTag(SettingsTestTags.RETENTION, "the retention control")
        val before = retentionLabel()

        compose.onNodeWithTag(SettingsTestTags.RETENTION_MORE).performClick()

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && retentionLabel() == before) {
            compose.waitForIdle()
            Thread.sleep(POLL_MS)
        }
        if (retentionLabel() == before) {
            fail("retention did not change from '$before'\n${compose.onRoot().printToString()}")
        }
    }

    /** Which build this is — the question every field report so far has turned on. */
    @Test
    fun settingsSaysWhichBuildThisIs() {
        openSettings()
        awaitTag(SettingsTestTags.ABOUT, "the build identity")
    }

    private fun retentionLabel(): String = compose.onAllNodesWithTag(SettingsTestTags.RETENTION)
        .fetchSemanticsNodes()
        .firstOrNull()
        ?.let { node ->
            node.config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString { it.text }
        }
        .orEmpty()

    private fun openSettings() {
        awaitTag(HomeTestTags.SCREEN, "home")
        awaitText(SETTINGS_PILL, "the Settings pill on Home")
        compose.onAllNodesWithText(SETTINGS_PILL)[0].performClick()
        awaitTag(SettingsTestTags.SCREEN, "the settings screen")
    }

    private fun awaitTag(tag: String, what: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(POLL_MS)
        }
        fail("$what: no node tagged '$tag' within ${TIMEOUT_MS}ms\n${compose.onRoot().printToString()}")
    }

    private fun awaitText(text: String, what: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(POLL_MS)
        }
        fail("$what: no node reading '$text' within ${TIMEOUT_MS}ms\n${compose.onRoot().printToString()}")
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
        const val SETTINGS_PILL = "Settings"
    }
}
