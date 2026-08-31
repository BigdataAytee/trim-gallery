package app.trimgallery.ui

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app starts.
 *
 * Everything else in this repository is checked without a device: the shared logic on the
 * JVM, the boundaries by the build guards, compilation on four targets in CI. None of that
 * can catch the things that only happen when Android actually loads the app — a missing
 * native library, a Compose theme that resolves at compile time and throws at inflate time,
 * a Koin graph with a cycle, a manifest that merges to something the launcher will not
 * start. Each of those is a crash on first run from a build that went green.
 *
 * So this asserts the smallest thing that exercises all of them at once, and nothing more.
 * It is deliberately not a UI test: what appears on the screen is DESIGN_SYSTEM.md's
 * business and belongs in tests that can afford to be wrong about layout. This one only
 * says the process came up, the activity reached RESUMED, and it survived being stopped.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {

    @Test
    fun mainActivityReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Reading the native library through the activity would couple this test to
                // the engine's wiring; that libtrim_native.so is present and loadable is
                // the APK check's job. Here it is enough that nothing threw on the way up.
                assertEquals(false, activity.isFinishing)
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /**
     * Rotation and process-death recreate the activity, and a crash there is as fatal to a
     * user as one at launch — it just takes one screen rotation longer to find.
     */
    @Test
    fun mainActivitySurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
