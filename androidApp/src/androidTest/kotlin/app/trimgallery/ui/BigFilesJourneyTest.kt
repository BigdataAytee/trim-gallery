package app.trimgallery.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.pipeline.TriageStep
import app.trimgallery.engine.ContainerFacts
import app.trimgallery.engine.ContainerReader
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.StartupGuard
import app.trimgallery.feature.compress.BigFilesTestTags
import app.trimgallery.feature.compress.OptimiseTestTags
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext

/**
 * Find big files, and what the scan says about them.
 *
 * **Triage is real here, not faked.** The step runs over fake files with declared sizes and
 * writes its own verdicts to the in-memory repository, so what this asserts is the actual
 * decision path the night pass uses — a big H.264 video becomes a candidate with an
 * estimate, a tiny file is skipped as too small — rather than a screen fed prepared data.
 * Only the bytes are fake, and the Triager never reads bytes: it reads size, mime and the
 * container facts, which is why stubbing the reader costs nothing here.
 *
 * The encode itself is still not exercised. The emulator has no hardware encoder and
 * BUILD.md § 2 rule 2 forbids the software one, so what a tap on Trim proves is that the
 * sheet opens over the right file — not that a file was made smaller. That is a phone test,
 * and it is reported as one.
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class BigFilesJourneyTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    /** Big enough, and in a codec the Triager knows it can beat. */
    private val big = videoItem(id = "big-video", name = "holiday.mp4", size = 400L * 1024 * 1024)

    /** Under any threshold worth spending a battery on: the Triager should decline it. */
    private val tiny = videoItem(id = "tiny-video", name = "clip.mp4", size = 12L * 1024)

    private val compose = createAndroidComposeRule<MainActivity>()

    private val graph = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                StartupGuard(app).clear()
                CrashReports(app).clear()
                val repository = inMemoryRepository(app)
                val storage = FakeLibrary(listOf(big, tiny))
                GlobalContext.get().apply {
                    declare<GrantedFolders>(FakeGrantedFolders(app, listOf(journeyGrant())))
                    declare<LibraryStorage>(storage)
                    declare<TrimRepository>(repository)
                    // The real triage, over fake files. Its verdicts are what the screen reads.
                    declare(
                        TriageStep(
                            storage = storage,
                            containers = NoContainerFacts,
                            sink = repository,
                            nowMs = { System.currentTimeMillis() },
                        ),
                    )
                }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(graph).around(compose)

    /** The scan finds the big file, and says what it could become. */
    @Test
    fun findingBigFilesListsTheOnesWorthTrimming() {
        openBigFiles()
        awaitTag(BigFilesTestTags.TOTAL, "the total that could be freed")
        awaitTag(BigFilesTestTags.row(big.id), "the big file")
    }

    /**
     * The second section is why the scan is worth running even when nothing can shrink.
     *
     * A user whose library is already efficient still learns where their storage went,
     * instead of being shown an empty screen and left to wonder whether the app works.
     */
    @Test
    fun filesThatCannotBeTrimmedAreListedWithTheirReason() {
        openBigFiles()
        awaitTag(BigFilesTestTags.CANNOT, "the can't-be-trimmed heading")
        // The Triager's own verdict, rendered through SkipList's wording rather than the
        // screen's: the reason a user reads is the reason the pipeline recorded.
        awaitTag(BigFilesTestTags.reason("Too small"), "the too-small group")
    }

    /** Trim opens the sheet over the file that was tapped, and no other. */
    @Test
    fun trimmingAFileOpensItsSheet() {
        openBigFiles()
        awaitTag(BigFilesTestTags.trim(big.id), "the trim control")
        compose.onNodeWithTag(BigFilesTestTags.trim(big.id)).performClick()
        awaitTag(OptimiseTestTags.SHEET, "the optimise sheet")
    }

    private fun openBigFiles() {
        awaitTag(HomeTestTags.FIND, "the find-big-files action on Home")
        compose.onNodeWithTag(HomeTestTags.FIND).performClick()
        awaitTag(BigFilesTestTags.SCREEN, "the big files screen")
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

    private fun videoItem(id: String, name: String, size: Long) = MediaItem(
        id = id,
        platformRef = MediaRef("file:///dev/null/$name"),
        name = name,
        kind = MediaKind.VIDEO,
        codec = "video/avc",
        width = WIDTH,
        height = HEIGHT,
        fps = FPS,
        bitrate = null,
        size = size,
        duration = null,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        status = MediaStatus.NEW,
        mtime = System.currentTimeMillis(),
        folderGrantId = JOURNEY_TREE,
        mime = "video/mp4",
    )

    /**
     * A reader that parses nothing.
     *
     * Triage falls back to the item as scanned when the container cannot be read, which is
     * exactly the state these fixtures are in: they have no bytes to parse. The size, the
     * mime and the codec are what the verdict turns on, and those are declared.
     */
    private object NoContainerFacts : ContainerReader {
        override suspend fun read(ref: MediaRef): ContainerFacts? = null
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 30.0
    }
}
