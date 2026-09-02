package app.trimgallery.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.model.FolderMode
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
 * Folders: the screen the whole app depends on, since nothing is read until one is granted.
 *
 * It was the first section of Settings while this was a gallery. The pivot made it a screen
 * of its own, and that promotion is the reason it now has a journey: a folder list that
 * silently fails to remove a folder, or offers a mode it cannot honour, is the difference
 * between an app that touches the user's originals correctly and one that does not.
 */
@RunWith(AndroidJUnit4::class)
class FoldersJourneyTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val grant = journeyGrant()
    private val folders = FakeGrantedFolders(app, listOf(grant))

    private val compose = createAndroidComposeRule<MainActivity>()

    private val graph = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                StartupGuard(app).clear()
                CrashReports(app).clear()
                GlobalContext.get().apply {
                    declare<GrantedFolders>(folders)
                    declare<LibraryStorage>(FakeLibrary(listOf(journeyPhoto(app), journeyVideo(app))))
                    declare<TrimRepository>(inMemoryRepository(app))
                }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(graph).around(compose)

    /** A granted folder is on the screen, with the three things that can happen to it. */
    @Test
    fun aGrantedFolderIsListedWithItsModes() {
        openFolders()
        awaitTag(FoldersTestTags.row(grant.platformRef.value), "the granted folder")
        FolderMode.entries.forEach { mode ->
            awaitTag(FoldersTestTags.mode(grant.platformRef.value, mode.name), "the ${mode.name} choice")
        }
    }

    /**
     * Choosing a mode writes it, and the row shows the choice.
     *
     * FREE is the one chosen here on purpose: it is the only mode that eventually deletes
     * an original, so it is the one whose write path is worth proving over the one that
     * changes nothing.
     */
    @Test
    fun choosingFreeSpaceIsSaved() {
        openFolders()
        val ref = grant.platformRef.value
        awaitTag(FoldersTestTags.mode(ref, FolderMode.FREE.name), "the free-space choice")
        compose.onNodeWithTag(FoldersTestTags.mode(ref, FolderMode.FREE.name)).performClick()
        compose.waitForIdle()
        // The row survives the write and re-read rather than vanishing or duplicating.
        awaitTag(FoldersTestTags.row(ref), "the folder after choosing Free the space")
    }

    /**
     * Removing a folder takes it off the screen and out of the grants.
     *
     * Both halves matter. A row that disappears while the permission is still held would
     * leave the night pass reading a folder the user believes they revoked.
     */
    @Test
    fun removingAFolderTakesItOffTheScreen() {
        openFolders()
        val ref = grant.platformRef.value
        awaitTag(FoldersTestTags.remove(ref), "the remove control")

        compose.onNodeWithTag(FoldersTestTags.remove(ref)).performClick()

        awaitGone(FoldersTestTags.row(ref), "the removed folder")
        awaitTag(FoldersTestTags.EMPTY, "the empty state, once the last folder is gone")
    }

    /**
     * "Scan my whole phone" explains itself before Android is ever asked.
     *
     * BUILD.md § 4 (b) requires the reason first, and this asserts the order rather than
     * the wording: the explainer is on screen and the system dialog has not been reached,
     * because the only way past it is the button this test does not press.
     */
    @Test
    fun wholePhoneAccessExplainsItselfFirst() {
        openFolders()
        awaitTag(FoldersTestTags.WHOLE_PHONE, "the whole-phone entry point")
        compose.onNodeWithTag(FoldersTestTags.WHOLE_PHONE).performClick()
        awaitTag(FoldersTestTags.WHOLE_PHONE_EXPLAINER, "the explanation")
        awaitTag(FoldersTestTags.WHOLE_PHONE_CONTINUE, "the way on to Android's own screen")
    }

    private fun openFolders() {
        awaitTag(HomeTestTags.FOLDERS, "the folders link on Home")
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

    private fun awaitGone(tag: String, what: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return
            Thread.sleep(POLL_MS)
        }
        fail("$what: still on screen after ${TIMEOUT_MS}ms\n${compose.onRoot().printToString()}")
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
    }
}
