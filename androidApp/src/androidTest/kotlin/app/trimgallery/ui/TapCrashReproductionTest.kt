package app.trimgallery.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeDown
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.model.MediaItem
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.StartupGuard
import app.trimgallery.feature.gallery.GalleryTestTags
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext

/**
 * "Trim Gallery keeps stopping the moment I tap a picture." Four builds, no stack trace.
 *
 * This test exists to make the emulator fail the way the phone fails, which the two
 * existing journeys demonstrably do not — they tap a photo and a video and stay green while
 * a phone crashes. So the question is not "does the app crash on tap" but "what is different
 * about the phone", and this test removes the differences one at a time until the emulator
 * agrees with the phone. Everything the earlier journeys replaced or simplified is real here:
 *
 *   * **The real Activity.** `GalleryJourneyTest` hosts `GalleryHost` inside a bare
 *     `ComponentActivity`. `MainActivityGrantedLaunchTest` runs the real `MainActivity` —
 *     and never taps anything. Nothing had ever tapped a tile inside the Activity a phone
 *     actually runs: its theme, its window, its insets, its startup guard, its Koin graph.
 *   * **`content://` URIs.** Every fixture used `file://`, and said so in a comment. A phone's
 *     library arrives as SAF document URIs, and Coil, the frame retriever and ExoPlayer
 *     each take a different path for those. `JourneyDocumentsProvider` serves the same
 *     files the way a provider does.
 *   * **Real Coil, real thumbnails.** The `artwork` slot is `Thumbnail`, which is the
 *     production `AsyncImage` over the production `ImageLoader` the Application installed.
 *   * **A photograph the size a camera writes.** Eight megapixels, not a 64-pixel cyan
 *     square. The decode, the bitmap and the transition all scale with it.
 *   * **A library, not two files.** Enough items that the grid has sections and the pager
 *     has neighbours.
 *   * **Twenty taps each**, because a crash that depends on state left by the previous
 *     open — a leaked bitmap, a player not released, a pager that remembers — is not one a
 *     single tap can find.
 *
 * If this stays green, that is a finding too: it means the phone's difference is somewhere
 * this list does not reach — the API level, the device's own provider, the real picker's
 * grant — and the next test removes one of those.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TapCrashReproductionTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val photos: List<MediaItem> = (0 until PHOTOS).map { documentPhoto(app, it) }
    private val videos: List<MediaItem> = (0 until VIDEOS).map { documentVideo(app, it) }

    private val compose = createAndroidComposeRule<MainActivity>()

    /** The graph the real Activity resolves, with the platform's two answers replaced. */
    private val graph = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                StartupGuard(app).clear()
                CrashReports(app).clear()
                GlobalContext.get().apply {
                    declare<GrantedFolders>(FakeGrantedFolders(app, listOf(journeyGrant())))
                    declare<LibraryStorage>(FakeLibrary(photos + videos))
                    declare<TrimRepository>(inMemoryRepository(app))
                }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(graph).around(compose)

    @Test
    fun tappingPhotosTwentyTimesInTheRealActivityDoesNotCrash() {
        repeat(TAPS) { round -> openAndClose(photos[round % photos.size], round) }
    }

    @Test
    fun tappingVideosTwentyTimesInTheRealActivityDoesNotCrash() {
        repeat(TAPS) { round -> openAndClose(videos[round % videos.size], round) }
    }

    /**
     * One tap, one look, one dismissal.
     *
     * The wait after the viewer appears is not padding. The viewer node exists the instant
     * the hero transition starts; the full-size decode, the player's prepare and the
     * shared-element animation all happen *after* that, and a crash in any of them lands
     * during this window. Returning the moment the tag appears would pass a viewer that
     * crashes a frame later.
     */
    private fun openAndClose(item: MediaItem, round: Int) {
        awaitTag(GalleryTestTags.tile(item.id), "round $round: tile")
        compose.onNodeWithTag(GalleryTestTags.tile(item.id)).performClick()

        awaitTag(GalleryTestTags.VIEWER, "round $round: viewer after tapping ${item.name}")
        settle()
        assertNoCrash("round $round: after opening ${item.name}")

        // Drag-to-dismiss, which is how the viewer closes; there is no back handler.
        compose.onNodeWithTag(GalleryTestTags.VIEWER).performTouchInput { swipeDown() }
        awaitGone(GalleryTestTags.VIEWER, "round $round: viewer after dismissing ${item.name}")
        settle()
        assertNoCrash("round $round: after closing ${item.name}")
    }

    /**
     * A crash the app's own handler caught.
     *
     * An uncaught exception on the main thread ends the instrumentation with the trace, so
     * most crashes report themselves. This catches the other shape: one that
     * `TrimGalleryApplication`'s handler recorded and something downstream survived.
     */
    private fun assertNoCrash(where: String) {
        val reports = CrashReports(app).reports()
        assertTrue(
            "$where: the app recorded a crash\n${CrashReports(app).asReport()}",
            reports.isEmpty(),
        )
    }

    /** Real time for the decode and the transition to happen in. */
    private fun settle() {
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
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
        fail(
            "$what: a node tagged '$tag' is still on screen after ${TIMEOUT_MS}ms\n${compose.onRoot().printToString()}",
        )
    }

    private companion object {
        const val PHOTOS = 6
        const val VIDEOS = 3
        const val TAPS = 20
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L

        /** Long enough for an 8-megapixel decode and a 300 ms hero transition to finish. */
        const val SETTLE_MS = 700L
    }
}
