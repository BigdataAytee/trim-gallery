package app.trimgallery.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowPlanTest {

    private fun assertNoOverlap(windows: List<WindowPlan.Window>) {
        windows.zipWithNext { a, b ->
            assertTrue(b.startMs >= a.endMs, "windows overlap: $a then $b")
        }
    }

    private fun assertInside(windows: List<WindowPlan.Window>, durationMs: Long) {
        windows.forEach {
            assertTrue(it.startMs >= 0 && it.endMs <= durationMs, "$it runs outside 0..$durationMs")
        }
    }

    @Test
    fun `a short clip gets one window from the middle`() {
        // BUILD.md section 5: one 5-second window from the middle.
        val windows = WindowPlan.probeWindows(60_000)
        assertEquals(1, windows.size)
        assertEquals(WindowPlan.WINDOW_MS, windows[0].lengthMs)
        assertEquals(27_500, windows[0].startMs)
    }

    @Test
    fun `a long clip gets three probe windows`() {
        // "three windows for files > 3 min"
        val windows = WindowPlan.probeWindows(600_000)
        assertEquals(3, windows.size)
        assertInside(windows, 600_000)
        assertNoOverlap(windows)
    }

    @Test
    fun `the three-minute boundary is exclusive`() {
        assertEquals(1, WindowPlan.probeWindows(WindowPlan.MULTI_WINDOW_THRESHOLD_MS).size)
        assertEquals(3, WindowPlan.probeWindows(WindowPlan.MULTI_WINDOW_THRESHOLD_MS + 1).size)
    }

    @Test
    fun `a clip shorter than one window is scored whole`() {
        val windows = WindowPlan.probeWindows(2_000)
        assertEquals(1, windows.size)
        assertEquals(0, windows[0].startMs)
        assertEquals(2_000, windows[0].lengthMs)
    }

    @Test
    fun `a zero-length clip has nothing to probe`() {
        assertEquals(emptyList(), WindowPlan.probeWindows(0))
        assertEquals(emptyList(), WindowPlan.verifyWindows(0))
        assertEquals(emptyList(), WindowPlan.probeWindows(-1))
    }

    @Test
    fun `verification always samples start, middle and end`() {
        // A re-encode can fail in one part of a file and hold up elsewhere.
        val windows = WindowPlan.verifyWindows(120_000)
        assertEquals(3, windows.size)
        assertEquals(0, windows.first().startMs)
        assertEquals(120_000, windows.last().endMs)
        assertNoOverlap(windows)
    }

    @Test
    fun `verification windows never overlap on a clip too short for three`() {
        // Overlapping would weight the same frames twice in the average.
        val windows = WindowPlan.verifyWindows(12_000)
        assertInside(windows, 12_000)
        assertNoOverlap(windows)
        assertTrue(windows.size in 1..3, "got ${windows.size}")
    }

    @Test
    fun `every window stays inside the file at a range of durations`() {
        listOf(1_000L, 4_999L, 5_000L, 5_001L, 14_999L, 15_000L, 179_999L, 180_001L, 3_600_000L)
            .forEach { duration ->
                assertInside(WindowPlan.probeWindows(duration), duration)
                assertInside(WindowPlan.verifyWindows(duration), duration)
                assertNoOverlap(WindowPlan.probeWindows(duration))
                assertNoOverlap(WindowPlan.verifyWindows(duration))
            }
    }

    @Test
    fun `total decoded time is the sum of the windows`() {
        val windows = WindowPlan.probeWindows(600_000)
        assertEquals(3 * WindowPlan.WINDOW_MS, WindowPlan.totalMs(windows))
    }

    @Test
    fun `scoring happens at 720p`() {
        // BUILD.md section 5. Scoring at source resolution would make the search cost
        // scale with exactly the files that most need optimising.
        assertEquals(720, WindowPlan.SCORING_HEIGHT)
    }

    @Test
    fun `Careful mode tiles the whole file with no gaps and no overlap`() {
        val windows = WindowPlan.fullFileWindows(32_000)
        assertEquals(7, windows.size)
        assertEquals(32_000L, WindowPlan.totalMs(windows))
        windows.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endMs, b.startMs, "gap or overlap between $a and $b")
        }
        assertEquals(0L, windows.first().startMs)
        assertEquals(32_000L, windows.last().endMs)
    }

    @Test
    fun `a file shorter than one window is a single short Careful window`() {
        val windows = WindowPlan.fullFileWindows(1_200)
        assertEquals(listOf(WindowPlan.Window(0, 1_200)), windows)
    }

    @Test
    fun `an exact multiple of the window length does not produce a zero-length tail`() {
        val windows = WindowPlan.fullFileWindows(15_000)
        assertEquals(3, windows.size)
        assertTrue(windows.none { it.lengthMs == 0L })
    }

    @Test
    fun `a file with no duration has nothing to verify`() {
        assertTrue(WindowPlan.fullFileWindows(0).isEmpty())
    }
}
