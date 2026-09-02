package app.trimgallery.ui

import android.content.Context
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
import app.trimgallery.feature.settings.FoldersTestTags
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext

/**
 * Home, in the Activity a phone actually launches.
 *
 * The first screen of the app after the pivot, and the first journey written against it.
 * The gallery's five journeys went with the gallery; this begins putting that coverage
 * back, one screen per change, over the same fixture harness they used — which is why that
 * harness was kept when everything around it was deleted.
 *
 * What this deliberately does **not** assert is a saving. Home shows none: what a file
 * could save is the probe's per-file question and Big files is the screen that asks it. A
 * journey expecting a number here would be describing a screen nobody decided to build.
 */
@RunWith(AndroidJUnit4::class)
class HomeJourneyTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private val compose = createAndroidComposeRule<MainActivity>()

    /** The graph the real Activity resolves, with the platform's two answers replaced. */
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

    /** The three things Home promises to say, on the screen a launcher icon opens. */
    @Test
    fun homeSaysWhatWasFreedAndWhenTheNextRunIs() {
        awaitTag(HomeTestTags.SCREEN, "home")
        awaitTag(HomeTestTags.FREED, "the freed total")
        awaitTag(HomeTestTags.NEXT_RUN, "the schedule line")
        awaitTag(HomeTestTags.TOGGLE, "the overnight switch")
    }

    /**
     * The switch is the user's standing instruction, so it has to survive being used.
     *
     * Asserted through the label, because that is the only part of it the user can see: a
     * switch that persists correctly while still offering to do what it just did is broken
     * in the way that matters.
     */
    @Test
    fun theOvernightSwitchChangesWhatHomeOffers() {
        awaitTag(HomeTestTags.TOGGLE, "the overnight switch")
        awaitText(TURN_OFF, "the switch should start on, because the setting defaults to on")

        compose.onNodeWithTag(HomeTestTags.TOGGLE).performClick()

        awaitText(TURN_ON, "after switching off, the switch should offer to switch back on")
    }

    /** Home reaches Folders, because after the pivot nothing else in the app does. */
    @Test
    fun homeOpensFolders() {
        awaitTag(HomeTestTags.FOLDERS, "the folders link")
        compose.onNodeWithTag(HomeTestTags.FOLDERS).performClick()
        awaitTag(FoldersTestTags.SCREEN, "the folders screen")
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
        const val TURN_OFF = "Turn overnight trimming off"
        const val TURN_ON = "Turn overnight trimming on"
    }
}
