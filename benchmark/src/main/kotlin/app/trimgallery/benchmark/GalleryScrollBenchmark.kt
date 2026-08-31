package app.trimgallery.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUILD.md § 2.7: "The gallery UI must stay at display refresh rate while background
 * work runs." That is a claim about frame timing, so it is measured here rather than
 * asserted in a review.
 *
 * Skeleton: the grid it scrolls arrives with milestone 8. Until then this measures cold
 * start, which is already meaningful.
 */
@RunWith(AndroidJUnit4::class)
class GalleryScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun gridScroll() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
    ) {
        startActivityAndWait()
        val grid = device.wait(Until.findObject(By.res("gallery_grid")), GRID_TIMEOUT_MS)
            ?: return@measureRepeated // grid lands with milestone 8
        grid.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        repeat(SCROLLS) {
            grid.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE = "app.trimgallery"
        const val ITERATIONS = 5
        const val SCROLLS = 3
        const val GRID_TIMEOUT_MS = 5_000L
        const val GESTURE_MARGIN_DIVISOR = 5
    }
}
