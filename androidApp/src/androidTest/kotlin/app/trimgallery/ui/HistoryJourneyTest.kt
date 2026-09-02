package app.trimgallery.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import app.trimgallery.feature.space.HistoryTestTags
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext

/**
 * History, on a library nothing has run over yet.
 *
 * That is the state every new install is in, and it is the one worth a journey: the screen
 * has to say so plainly rather than showing an empty list, which reads as a bug on the
 * screen a user checks first when they are wondering whether the app did anything.
 *
 * A populated History with a live restore needs a `job` row and an `UndoEntry`, which needs
 * an encode, which the emulator cannot do — no hardware encoder, and BUILD.md § 2 rule 2
 * forbids the software one. The restore path itself is covered by unit tests over
 * `History.restorable` and by the iOS restore journey; what this adds is that the screen
 * mounts, reaches its totals, and tells the truth when there is nothing to show.
 */
@RunWith(AndroidJUnit4::class)
class HistoryJourneyTest {

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

    /** The screen opens, and leads with the number it exists to report. */
    @Test
    fun historyOpensAndShowsTheFreedTotal() {
        openHistory()
        awaitTag(HistoryTestTags.TOTAL, "the freed total")
    }

    /**
     * An empty History says why it is empty.
     *
     * "Nothing has been optimised yet" is not an apology and not an error — a library that
     * has never been run over looks exactly like one where everything was already
     * efficient, and both deserve a sentence rather than a blank page.
     */
    @Test
    fun anEmptyHistorySaysSoRatherThanShowingNothing() {
        openHistory()
        awaitTag(HistoryTestTags.EMPTY, "the empty explanation")
    }

    /** History is reached from the pill over Home, which is the only way in. */
    private fun openHistory() {
        awaitTag(HomeTestTags.SCREEN, "home")
        awaitText(HISTORY_PILL, "the History pill on Home")
        compose.onAllNodesWithText(HISTORY_PILL)[0].performClick()
        awaitTag(HistoryTestTags.SCREEN, "the history screen")
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
        const val HISTORY_PILL = "History"
    }
}
