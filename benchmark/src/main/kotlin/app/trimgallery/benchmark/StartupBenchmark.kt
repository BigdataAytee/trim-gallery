package app.trimgallery.benchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long the grid takes to arrive, which is the number the second field report was about.
 *
 * The target is **under a second from a warm start**. Cold and warm are measured separately
 * because they fail for different reasons: a slow cold start is process creation, class
 * loading and the first frame; a slow warm start is work the app is doing that it should not
 * be doing again.
 *
 * What this measures and what it does not: `StartupTimingMetric` reports `timeToInitialDisplay`
 * — the first frame — and `timeToFullDisplay` when the app calls `reportFullyDrawn`. The
 * change these numbers are meant to show is that the grid is drawn from the database before
 * any folder is walked, so the first frame no longer waits on a SAF walk of every granted
 * tree.
 *
 * **The numbers depend entirely on the library on the device.** An empty install starts fast
 * whatever the code does; the measurement that means anything is on a phone with a real
 * DCIM folder, which is why the field-test protocol and not CI is where these are read.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * Process creation included: the app is killed and started fresh.
     *
     * This is the honest worst case and the one a user sees after a reboot or after the
     * system reclaims the app overnight — which, for an app whose whole job runs at 3am, is
     * most mornings.
     */
    @Test
    fun coldStart() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }

    /**
     * The process survives; the Activity is recreated.
     *
     * The one the target is written against, and the one that exposes repeated work: a warm
     * start that walks every granted folder again is doing the whole scan for a library it
     * already has.
     */
    @Test
    fun warmStart() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
    ) {
        pressHome()
        startActivityAndWait()
    }

    private companion object {
        const val PACKAGE = "app.trimgallery"

        /**
         * Five, matching `GalleryScrollBenchmark`.
         *
         * Its own copy rather than a shared one: that class keeps its constants private,
         * and a benchmark that silently changed because a neighbour retuned its iteration
         * count would make two runs incomparable for a reason nothing on screen explains.
         */
        const val ITERATIONS = 5
    }
}
