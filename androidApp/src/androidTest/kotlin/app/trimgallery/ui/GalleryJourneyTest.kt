package app.trimgallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trimgallery.core.data.AndroidDatabase
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.Settings
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.NightPass
import app.trimgallery.engine.android.StartupGuard
import app.trimgallery.feature.gallery.GalleryTestTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The journeys a person actually makes through this app, on a device.
 *
 * Everything else in this repository is checked without one: the shared logic on the JVM,
 * the boundaries by the build guards, four targets compiling in CI, and one instrumented
 * test that says the Activity reaches RESUMED. Three builds went out green on all of that
 * while the first screen crashed the moment a folder was granted, then crashed on every
 * launch afterwards. RESUMED says the process came up. It says nothing about what is on
 * the screen, or what happens when it is touched.
 *
 * So these tap. Grant a folder and the grid must render; tap a photograph and the viewer
 * must open; tap a video and it must play; come back a second time and the photographs
 * must still be there; and a startup that does fail must land on a screen rather than take
 * the process with it. Each one is a bug that reached a phone, written as the thing the
 * phone would have refused to do.
 *
 * What is faked is only what an emulator cannot supply — the system picker's grant, a tree
 * of the user's own files, WorkManager (see `JourneyFakes.kt`). The database is the real
 * one, opened through the real `AndroidSqliteDriver` and its callback, because the crash
 * loop was a foreign key that is **on** there and **off** in the JVM driver the unit tests
 * use: seven passing unit tests certified the bug. Held in memory rather than in the app's
 * own file so a run leaves nothing behind and starts from nothing.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class GalleryJourneyTest {

    // ComponentActivity rather than MainActivity: MainActivity calls setContent in onCreate,
    // and a test that then sets its own content would compose the app twice. The empty host
    // is declared in the smoke variant's manifest, which is the only build that has it.
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Context = ApplicationProvider.getApplicationContext()

    private lateinit var photo: MediaItem
    private lateinit var video: MediaItem
    private lateinit var library: FakeLibrary
    private lateinit var scheduler: RecordingScheduler
    private lateinit var repository: TrimRepository
    private lateinit var picker: ActivityResultRegistryOwner

    @Before
    fun prepare() {
        // Whatever a previous test left set. The guard is a file, and it outlives a process.
        StartupGuard(app).complete()

        photo = photoItem(writeJpeg("journey.jpg"))
        video = videoItem(copyOutOfAssets(GOLDEN))
        library = FakeLibrary(listOf(video, photo))
        scheduler = RecordingScheduler()
        repository = inMemoryRepository()
        picker = pickerReturning(Uri.parse(TREE))
    }

    /**
     * Grant a folder, and the photographs appear.
     *
     * The crash was inside this: `media_item.folder_grant_id` references `folder_grant(id)`,
     * foreign keys are on, and nothing wrote the grant row — so the first scan's first insert
     * threw, and threw again on every launch because the grant itself had been persisted.
     */
    @Test
    fun grantingAFolderRendersTheGrid() {
        showGallery(FakeGrantedFolders(app))

        awaitText(CHOOSE_FOLDER)
        compose.onNodeWithText(CHOOSE_FOLDER).assertIsDisplayed()
        compose.onNodeWithText(CHOOSE_FOLDER).performClick()

        awaitTag(GalleryTestTags.GRID)
        compose.onNodeWithTag(GalleryTestTags.tile(photo.id)).assertExists()
        compose.onNodeWithTag(GalleryTestTags.tile(video.id)).assertExists()

        assertTrue("granting a folder is the moment there is work, so it must schedule", scheduler.scheduled)
        runBlocking {
            assertNotNull("the grant row every media row points at", repository.folderGrant(TREE))
            assertEquals("both files persisted", 2, repository.gallery().size)
        }
    }

    /**
     * Tap a photograph and the viewer opens.
     *
     * "Tapping a photo closes the app" was a tile that had not reported its rectangle yet,
     * a fallback that returned a negative one, and `Modifier.size` throwing on it. Nothing
     * caught it because the geometry tests build their rectangles directly and never go
     * through a tile.
     */
    @Test
    fun tappingAPhotoOpensTheViewer() {
        showGallery(FakeGrantedFolders(app, listOf(grant())))
        awaitTag(GalleryTestTags.tile(photo.id))

        compose.onNodeWithTag(GalleryTestTags.tile(photo.id)).performClick()

        awaitTag(GalleryTestTags.VIEWER)
    }

    /**
     * Tap a video and it plays.
     *
     * Not "the viewer opened over it": a viewer above a player that never starts is the
     * black-tile bug one screen along, and the report that started this was about videos
     * specifically. So this waits for the player to reach READY and then to actually be
     * playing, over the golden clip — a real H.264 file with a real AAC track.
     */
    @Test
    fun tappingAVideoPlaysIt() {
        showGallery(FakeGrantedFolders(app, listOf(grant())))
        awaitTag(GalleryTestTags.tile(video.id))

        compose.onNodeWithTag(GalleryTestTags.tile(video.id)).performClick()

        awaitTag(GalleryTestTags.VIEWER)
        await("the player reaches READY") { playbackState() == Player.STATE_READY }
        await("the video is playing") { playing() }
    }

    /**
     * Come back a second time, and the photographs are there before anything is walked.
     *
     * Two things at once, because they are the same journey: the fast start (the grid is
     * drawn from the database, not from a walk of every granted tree) and the crash loop
     * (the second launch is where it always died, since the first one had persisted the
     * grant). The walk is held shut for the second launch, so a tile on screen can only
     * have come from the database.
     */
    @Test
    fun relaunchingAfterAGrantRendersTheGrid() {
        val launch = mutableStateOf(0)
        showGallery(FakeGrantedFolders(app, listOf(grant())), launch)
        awaitTag(GalleryTestTags.tile(photo.id))

        val walk = CompletableDeferred<Unit>()
        library.gate = walk
        compose.runOnUiThread { launch.value += 1 }
        // The old host is gone and the new one has drawn its first frame — which is empty,
        // because `items` starts empty. So a tile after this is the second launch's tile.
        compose.waitForIdle()

        awaitTag(GalleryTestTags.tile(photo.id))
        compose.onNodeWithTag(GalleryTestTags.tile(video.id)).assertExists()

        // Then let the walk through, and require the startup work to come back. The mark is
        // cleared only by work that finished; still set, it *is* the crash loop.
        await("the second launch starts its own work") { StartupGuard(app).previousRunFailed }
        walk.complete(Unit)
        await("the second launch's startup work to finish") { !StartupGuard(app).previousRunFailed }
        runBlocking { assertEquals("a rescan must not duplicate rows", 2, repository.gallery().size) }
    }

    /**
     * A startup that fails lands on the recovery screen, and does not try again.
     *
     * The crash loop was not one crash: it was a crash in work the app begins by itself over
     * an input that is still there next launch. So the fix is two halves and this asserts
     * both — the failure becomes a screen instead of a dead process, and the mark it leaves
     * behind is still set afterwards, which is what stops the next launch repeating it.
     *
     * The failure is the original one's exact shape: a scanned item pointing at a grant no
     * row was ever written for, which is a foreign key violation inside `applyScan`.
     */
    @Test
    fun aStartupThatFailsLandsOnRecoveryAndDoesNotRetry() {
        library = FakeLibrary(listOf(photo.copy(folderGrantId = "a-grant-nobody-recorded")))
        showGallery(FakeGrantedFolders(app, listOf(grant())))

        awaitTag(RECOVERY_TAG)

        assertTrue(
            "the mark must stay set, or the next launch runs the work that just died",
            StartupGuard(app).previousRunFailed,
        )
    }

    // ---------------------------------------------------------------- the host

    /**
     * The gallery, hosted the way `TrimApp` hosts it, over the faked edges.
     *
     * [launch] keyed around the host is a relaunch: changing it disposes the whole screen
     * and builds it again, which re-runs exactly the work a cold start runs. A fresh
     * [StartupGuard] per launch, because it reads the previous run's mark at construction.
     */
    private fun showGallery(folders: GrantedFolders, launch: MutableState<Int> = mutableStateOf(0)) {
        val nightPass = NightPass(scheduler = scheduler, folders = folders)
        compose.setContent {
            // reduceMotion, so the arrival and hero animations snap. What they look like is
            // DESIGN_SYSTEM.md's business and is asserted elsewhere; here they would only be
            // several hundred milliseconds between a tap and the assertion after it.
            TrimTheme(dark = true, reduceMotion = true) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    // The same two lines `TrimApp` has, for the same reason: a failure in the
                    // app's own startup work is not something to retry, so the gallery is not
                    // started again — a screen is shown instead.
                    var recovering by remember { mutableStateOf(false) }
                    if (recovering) {
                        RecoveryScreen(onContinue = { recovering = false }, modifier = Modifier.fillMaxSize())
                    } else {
                        key(launch.value) {
                            GalleryHost(
                                modifier = Modifier.fillMaxSize(),
                                onStartupFailure = { recovering = true },
                                storage = library,
                                repository = repository,
                                folders = folders,
                                nightPass = nightPass,
                                guard = StartupGuard(app),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The folder picker's answer, without the folder picker.
     *
     * `rememberLauncherForActivityResult` goes through whatever registry the composition
     * local holds, so replacing it hands the app a tree URI as though the user had chosen
     * one. Everything the app does with that result is its own code.
     */
    private fun pickerReturning(tree: Uri): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, tree as O)
            }
        }
    }

    private fun inMemoryRepository(): TrimRepository {
        var minted = 0
        return TrimRepository(
            // name = null: this driver, this callback, no file. The callback is where
            // `PRAGMA foreign_keys = ON` lives, and it is the whole reason these run here.
            db = AndroidDatabase.create(app, name = null),
            io = Dispatchers.IO,
            newId = { "journey-${minted++}" },
            nowMs = System::currentTimeMillis,
            readSettings = { Settings() },
            readTier = { Tier.FREE },
            monthStartMs = { 0L },
        )
    }

    // ------------------------------------------------------------- the fixtures

    /** As `GrantedFolders.grants()` builds it: the tree URI is both the id and the ref. */
    private fun grant() = FolderGrant(
        id = TREE,
        platformRef = MediaRef(TREE),
        mode = FolderMode.KEEP,
        displayName = "Journey",
    )

    private fun photoItem(file: File) = item(
        id = "journey-photo",
        file = file,
        kind = MediaKind.PHOTO,
        mime = "image/jpeg",
        mtime = 2_000L,
    )

    private fun videoItem(file: File) = item(
        id = "journey-video",
        file = file,
        kind = MediaKind.VIDEO,
        mime = "video/mp4",
        mtime = 1_000L,
    )

    @Suppress("LongParameterList")
    private fun item(id: String, file: File, kind: MediaKind, mime: String, mtime: Long) = MediaItem(
        id = id,
        // A file URI, not a SAF document URI, and that is the one seam: without a persisted
        // permission there is no document to address. Everything that reads it — Coil, the
        // frame extractor, ExoPlayer — goes through the same ContentResolver either way.
        platformRef = MediaRef(Uri.fromFile(file).toString()),
        name = file.name,
        kind = kind,
        codec = null,
        width = 0,
        height = 0,
        fps = null,
        bitrate = null,
        size = file.length(),
        duration = null,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        status = MediaStatus.NEW,
        mtime = mtime,
        folderGrantId = TREE,
        mime = mime,
    )

    /** A real photograph, so the viewer has something to decode rather than a missing path. */
    private fun writeJpeg(name: String): File {
        val bitmap = Bitmap.createBitmap(PHOTO_PX, PHOTO_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        return File(app.cacheDir, name).apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        }
    }

    private fun copyOutOfAssets(name: String): File = File(app.cacheDir, name).apply {
        outputStream().use { out ->
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.copyTo(out) }
        }
    }

    // ------------------------------------------------------------- the waiting

    private fun awaitTag(tag: String) = compose.waitUntil(SCREEN_TIMEOUT_MS) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitText(text: String) = compose.waitUntil(SCREEN_TIMEOUT_MS) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Polls in real time rather than through the Compose clock.
     *
     * What these wait for happens on other threads — ExoPlayer preparing, the scan
     * finishing, a `commit()` landing — and none of it is driven by frames. Sleeping on the
     * test thread leaves the main thread free to do exactly that work.
     */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + SCREEN_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        fail("timed out after ${SCREEN_TIMEOUT_MS}ms waiting for: $what")
    }

    private fun playbackState(): Int = onPlayer { it?.playbackState ?: Player.STATE_IDLE }

    private fun playing(): Boolean = onPlayer { it?.isPlaying == true }

    /**
     * Reads the player the viewer built, on the main thread, which is the only thread
     * `Player` may be touched from.
     */
    private fun <T> onPlayer(readPlayer: (Player?) -> T): T {
        val read = mutableListOf<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            read += readPlayer(playerViewIn(compose.activity.window.decorView)?.player)
        }
        return read.single()
    }

    private fun playerViewIn(view: View): PlayerView? = when (view) {
        is PlayerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { playerViewIn(view.getChildAt(it)) }
        else -> null
    }

    private companion object {
        /** A tree URI shaped like a picker's, on an authority no refusal rule names. */
        const val TREE = "content://app.trimgallery.journey/tree/journey%3ACamera"

        /** The wording on the first-run screen. */
        const val CHOOSE_FOLDER = "Choose folder"

        /** The clip from `shared/testdata`, real H.264 with a real AAC track. */
        const val GOLDEN = "golden-h264-640x360-3s.mp4"

        /**
         * Long enough for a cold emulator to boot a decoder, short enough that a hang is
         * reported as a failure rather than as a timed-out job with no reports.
         */
        const val SCREEN_TIMEOUT_MS = 30_000L
        const val POLL_MS = 50L
        const val PHOTO_PX = 64
        const val JPEG_QUALITY = 90
    }
}
