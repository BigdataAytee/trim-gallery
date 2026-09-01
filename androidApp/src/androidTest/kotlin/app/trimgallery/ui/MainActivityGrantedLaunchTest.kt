package app.trimgallery.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.model.MediaItem
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.StartupGuard
import app.trimgallery.feature.gallery.GalleryTestTags
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext

/**
 * The real Activity, with a folder already granted, must reach a grid.
 *
 * ## The gap this closes, which is the whole reason it exists
 *
 * Two instrumented suites covered this app and between them they missed the only
 * configuration that ships:
 *
 * - `MainActivityLaunchTest` launches the real `MainActivity` — but an emulator has no
 *   persisted folder grant, so `grants` is empty, the scan never starts, and it asserts
 *   that an app with nothing in it reaches RESUMED.
 * - `GalleryJourneyTest` exercises the granted path thoroughly — but hosts `GalleryHost`
 *   inside a bare `ComponentActivity` with fakes. It never runs `MainActivity`, never runs
 *   `TrimApp`, and never resolves anything from the real dependency graph.
 *
 * So **no test had ever run the real Activity with a folder granted**, and that is exactly
 * what a phone does on every launch after the first. A build went out where it crashed
 * before drawing anything, and both suites were green.
 *
 * This one runs `MainActivity` → `TrimApp` → `GalleryHost` over the real Koin graph, with
 * only the two things an emulator cannot supply replaced: the platform's list of granted
 * folders, and a tree of the user's own files.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MainActivityGrantedLaunchTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val photo: MediaItem = journeyPhoto(app)
    private val video: MediaItem = journeyVideo(app)

    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * The overrides have to be in place **before** the Activity launches, and the compose
     * rule launches it before `@Before` runs — so they go in an outer rule rather than a
     * setup method. `declare` replaces a definition in the graph the Application already
     * started; everything downstream resolves the replacement.
     */
    private val graph = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                // A launch that a previous test left marked as failed would open the
                // recovery screen instead of the gallery, and this test would fail for a
                // reason that has nothing to do with it.
                StartupGuard(app).clear()

                GlobalContext.get().apply {
                    // `declare` rather than loading and unloading a module, because its
                    // override semantics are explicit and it cannot fail on a definition that
                    // already exists. The declarations outlive this class — Koin is
                    // process-wide and there is no undeclare — which is harmless: every other
                    // instrumented test either passes its dependencies explicitly or asserts
                    // something a granted folder does not change.
                    declare<GrantedFolders>(FakeGrantedFolders(app, listOf(journeyGrant())))
                    declare<LibraryStorage>(FakeLibrary(listOf(video, photo)))
                    // In memory, so the test does not write to the database the person
                    // running the emulator may be looking at, and starts from nothing.
                    declare<TrimRepository>(inMemoryRepository(app))
                }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(graph).around(compose)

    @Test
    fun theRealActivityWithAFolderGrantedDrawsTheGrid() {
        compose.waitUntil(SCREEN_TIMEOUT_MS) {
            compose.onAllNodesWithTag(GalleryTestTags.tile(photo.id)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(GalleryTestTags.tile(video.id)).assertExists()
    }

    @Test
    fun theLaunchMarkIsClearedOnceAFrameIsDrawn() {
        // Not a detail of the guard, but the property the guard exists to provide: after a
        // launch that worked, the *next* launch must open the gallery rather than the
        // recovery screen. A mark that is never cleared would send every user there.
        compose.waitUntil(SCREEN_TIMEOUT_MS) {
            compose.onAllNodesWithTag(GalleryTestTags.tile(photo.id)).fetchSemanticsNodes().isNotEmpty()
        }

        assertFalse(
            "a launch that drew a frame must not look like a failure to the next one",
            StartupGuard(app).previousRunFailed,
        )
    }

    private companion object {
        const val SCREEN_TIMEOUT_MS = 30_000L
    }
}
