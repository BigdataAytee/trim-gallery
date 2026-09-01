package app.trimgallery.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.core.app.ActivityOptionsCompat
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.pipeline.video.VideoOptimiseStep
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.Setting
import app.trimgallery.engine.UndoStore
import app.trimgallery.engine.VideoCodec
import app.trimgallery.engine.android.NightPass
import app.trimgallery.engine.android.StartupGuard
import app.trimgallery.feature.compress.OptimiseTestTags
import app.trimgallery.feature.gallery.GalleryTestTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Long-press a video → Optimise, on a device.
 *
 * The step itself is faked, and that is not a shortcut around the interesting part — it is
 * the only option. The emulator image CI runs on has **no hardware encoder**, and BUILD.md
 * § 2 rule 2 forbids the software one, so a real encode cannot happen here at all. What this
 * proves is everything between the finger and the encoder, which is where this change is:
 * that the hold opens a sheet, that Start runs exactly one optimise, that the result is
 * reported with the measured numbers, that Undo restores, and — the one that matters most —
 * that a run which did **not** replace anything never offers an undo.
 *
 * The encode itself is proved on a phone, and reported as measured rather than inferred.
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class OptimiseJourneyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repository: TrimRepository
    private lateinit var video: MediaItem
    private lateinit var photo: MediaItem
    private val restored = mutableListOf<UndoEntry>()

    @Before
    fun prepare() = runBlocking {
        repository = inMemoryRepository(app)
        repository.recordGrants(listOf(journeyGrant()))
        video = journeyVideo(app)
        photo = journeyPhoto(app)
    }

    @Test
    fun holdingAVideoOffersToOptimiseIt() {
        show(step = neverFinishes())

        awaitTag(GalleryTestTags.tile(video.id))
        compose.onNodeWithTag(GalleryTestTags.tile(video.id)).performTouchInput { longClick() }

        awaitTag(OptimiseTestTags.SHEET)
        compose.onNodeWithTag(OptimiseTestTags.ESTIMATE).assertIsDisplayed()
        compose.onNodeWithTag(OptimiseTestTags.START).assertIsDisplayed()
    }

    @Test
    fun startingItReportsWhatChanged() {
        val step = finishes(
            VideoOptimiseStep.Result.Optimised(
                wasBytes = 380L * MB,
                nowBytes = 165L * MB,
                vmaf = 96.0,
                setting = Setting(bitrate = 4_000_000),
                codec = VideoCodec.HEVC,
                undo = MediaRef("bin://original"),
            ),
        )
        show(step)

        optimise()

        awaitTag(OptimiseTestTags.SUMMARY)
        // The sentence the whole screen exists to say, checked by its words on purpose.
        compose.onNodeWithText("Now 165 MB (was 380 MB)").assertIsDisplayed()
        compose.onNodeWithTag(OptimiseTestTags.UNDO).assertIsDisplayed()
        assertEquals("exactly one optimise per tap", 1, step.calls)
    }

    @Test
    fun theRunIsRecordedSoSpaceCanShowIt() {
        // A run that freed real space and left no row would report nothing on the Space
        // screen — which is the first place a user looks after tapping this button.
        val step = finishes(
            VideoOptimiseStep.Result.Optimised(
                wasBytes = 380L * MB,
                nowBytes = 165L * MB,
                vmaf = 96.0,
                setting = Setting(bitrate = 4_000_000),
                codec = VideoCodec.HEVC,
                undo = MediaRef("bin://original"),
            ),
        )
        show(step)

        optimise()
        awaitTag(OptimiseTestTags.SUMMARY)

        runBlocking {
            val jobs = repository.jobsFor(video.id)
            assertEquals("one job row for one tap", 1, jobs.size)
            assertEquals(JobState.SUCCEEDED, jobs.single().state)
            assertTrue("the row must say a person asked for it", jobs.single().userInitiated)
            assertEquals(165L * MB, jobs.single().newSize)
        }
    }

    @Test
    fun aRunThatReplacedNothingNeverOffersAnUndo() {
        // The failure this test exists for. A skip changes nothing, so an Undo button would
        // be one that cannot work — on the screen where the user is deciding whether to
        // trust this app with the rest of their library.
        show(finishes(VideoOptimiseStep.Result.Skipped(SkipReason.WOULD_NOT_SHRINK, "no smaller version")))

        optimise()

        awaitTag(OptimiseTestTags.SUMMARY)
        compose.onAllNodesWithTag(OptimiseTestTags.UNDO).assertCountEquals(0)
    }

    @Test
    fun undoPutsTheOriginalBack() {
        val step = finishes(
            VideoOptimiseStep.Result.Optimised(
                wasBytes = 380L * MB,
                nowBytes = 165L * MB,
                vmaf = 96.0,
                setting = Setting(bitrate = 4_000_000),
                codec = VideoCodec.HEVC,
                undo = MediaRef("bin://original"),
            ),
        )
        val parked = UndoEntry(
            id = "undo-1",
            mediaId = video.id,
            location = UndoLocation.BIN,
            ref = MediaRef("bin://original"),
            expiresAt = null,
        )
        runBlocking { repository.record(parked) }
        show(step)

        optimise()
        awaitTag(OptimiseTestTags.UNDO)
        compose.onNodeWithTag(OptimiseTestTags.UNDO).performClick()

        awaitTag(OptimiseTestTags.SUMMARY)
        assertEquals("the parked original is what gets restored", 1, restored.size)
        assertEquals(video.id, restored.single().mediaId)
    }

    // ------------------------------------------------------------------ driving

    private fun optimise() {
        awaitTag(GalleryTestTags.tile(video.id))
        compose.onNodeWithTag(GalleryTestTags.tile(video.id)).performTouchInput { longClick() }
        awaitTag(OptimiseTestTags.START)
        compose.onNodeWithTag(OptimiseTestTags.START).performClick()
    }

    /** A step that reports [result] immediately, counting how often it was asked. */
    private fun finishes(result: VideoOptimiseStep.Result) = CountingStep { result }

    /** A step that never returns, so the sheet stays where a test can look at it. */
    private fun neverFinishes() = CountingStep { CompletableDeferred<VideoOptimiseStep.Result>().await() }

    private class CountingStep(private val answer: suspend () -> VideoOptimiseStep.Result) :
        OptimiseController.Run {
        var calls = 0
            private set

        override suspend fun optimise(item: MediaItem, onProgress: (Float) -> Unit): VideoOptimiseStep.Result {
            calls++
            onProgress(HALFWAY)
            return answer()
        }
    }

    private inner class RecordingUndo : UndoStore {
        // Restores are recorded on the enclosing test so an assertion can read them.
        override suspend fun park(ref: MediaRef, mode: UndoLocation) = error("not used")
        override suspend fun restore(entry: UndoEntry) {
            restored += entry
        }

        override suspend fun sweep(nowEpochMs: Long) = Unit
    }

    private fun show(step: OptimiseController.Run) {
        val folders = FakeGrantedFolders(app, listOf(journeyGrant()))
        val library = FakeLibrary(listOf(video, photo))
        val nightPass = NightPass(scheduler = RecordingScheduler(), folders = folders)

        val undo = RecordingUndo()

        compose.setContent {
            TrimTheme(dark = true, reduceMotion = true) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides inertPicker()) {
                    val scope = rememberCoroutineScope()
                    // Remembered, so a recomposition mid-run does not build a second
                    // controller and lose the state the first one is holding.
                    val controller = remember {
                        OptimiseController(
                            scope = scope,
                            run = step,
                            undoStore = undo,
                            repository = repository,
                            tier = { Tier.FREE },
                            onLibraryChanged = {},
                            clocks = TestClocks,
                        )
                    }
                    GalleryHost(
                        modifier = Modifier.fillMaxSize(),
                        storage = library,
                        repository = repository,
                        folders = folders,
                        nightPass = nightPass,
                        guard = StartupGuard(app),
                        optimise = controller,
                    )
                }
            }
        }
    }

    /**
     * A registry that answers nothing.
     *
     * `GalleryHost` builds a folder picker whether or not a folder is already granted, and
     * `rememberLauncherForActivityResult` needs a registry to build one against. These
     * journeys never open it — the grant is already there — so it only has to exist.
     */
    private fun inertPicker(): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) = Unit
        }
    }

    /** Fixed ids and a real clock. Nothing here asserts on time, only that a row exists. */
    private object TestClocks : OptimiseController.Clocks {
        private var minted = 0
        override fun newJobId(): String = "job-${minted++}"
        override fun startOfTodayMs(): Long = 0L
        override fun now(): Instant = Clock.System.now()
    }

    private fun awaitTag(tag: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(POLL_MS)
        }
        fail("no node tagged '$tag' within ${TIMEOUT_MS}ms.\n${compose.onRoot().printToString()}")
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
        const val HALFWAY = 0.5f
    }
}
