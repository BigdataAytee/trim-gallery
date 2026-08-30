package app.trimgallery.core.domain.field

import app.trimgallery.core.domain.field.ThresholdFit.Point
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThresholdFitTest {

    /**
     * The real sweep from milestone 2 (`shared/native/calibration/README.md`): the golden
     * clip re-encoded across a CRF ladder with both metrics computed by this app's own
     * native code. It is the only measured XPSNR↔VMAF data that exists, so the fitting is
     * checked against it rather than against numbers invented for the test.
     */
    private val milestone2 = listOf(
        Point(xpsnr = 45.5016, vmaf = 98.086),
        Point(xpsnr = 42.5713, vmaf = 96.876),
        Point(xpsnr = 39.4061, vmaf = 94.747),
        Point(xpsnr = 37.5696, vmaf = 92.803),
        Point(xpsnr = 36.0164, vmaf = 90.035),
        Point(xpsnr = 34.7030, vmaf = 87.002),
        Point(xpsnr = 33.3120, vmaf = 83.132),
        Point(xpsnr = 30.9547, vmaf = 73.303),
    )

    private fun found(fit: ThresholdFit.Fit) = assertIs<ThresholdFit.Fit.Found>(fit)

    private fun close(expected: Double, actual: Double, tolerance: Double = 1e-3) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")
    }

    /** The README's published figure, reproduced by the code that will produce the next one. */
    @Test
    fun `the milestone 2 sweep fits VMAF 95 at XPSNR 39 point 8`() {
        val fit = found(ThresholdFit.fit(milestone2, targetVmaf = 95.0))
        close(39.782, fit.xpsnr)
        assertEquals(39.8, ThresholdFit.conservative(fit.xpsnr))
        assertEquals(8, fit.fromPoints)
    }

    @Test
    fun `a target that was measured exactly comes back exactly`() {
        val fit = found(ThresholdFit.fit(milestone2, targetVmaf = 90.035))
        close(36.0164, fit.xpsnr)
    }

    @Test
    fun `Compact's VMAF 90 lands just under the measured point`() {
        val fit = found(ThresholdFit.fit(milestone2, targetVmaf = 90.0))
        close(36.001, fit.xpsnr)
    }

    @Test
    fun `the order the points arrive in does not matter`() {
        val shuffled = milestone2.reversed()
        close(
            found(ThresholdFit.fit(milestone2, 95.0)).xpsnr,
            found(ThresholdFit.fit(shuffled, 95.0)).xpsnr,
        )
    }

    // ------------------------------------------------------------- refusals

    /**
     * Extrapolating past the end of a sweep is how a threshold nobody measured ends up
     * governing every encode in the library. The answer is to measure further.
     */
    @Test
    fun `a target beyond the sweep is refused, not extrapolated`() {
        for (target in listOf(99.5, 60.0)) {
            val fit = ThresholdFit.fit(milestone2, target)
            val reason = assertIs<ThresholdFit.Fit.Unfittable>(fit).reason
            assertEquals(ThresholdFit.Reason.OUT_OF_RANGE, reason, "$target")
        }
    }

    @Test
    fun `the ends of the sweep are inside it`() {
        found(ThresholdFit.fit(milestone2, 98.086))
        found(ThresholdFit.fit(milestone2, 73.303))
    }

    /**
     * Both metrics measure the same thing badly and well, so a real sweep rises. One that
     * does not is a broken measurement, and fitting through it would bake the noise into a
     * threshold that governs the whole library.
     */
    @Test
    fun `a sweep that is not monotone is refused`() {
        val broken = listOf(
            Point(30.0, 80.0),
            Point(34.0, 92.0),
            Point(38.0, 88.0),
            Point(42.0, 96.0),
        )
        assertFalse(ThresholdFit.isMonotone(broken))
        assertEquals(
            ThresholdFit.Reason.NOT_MONOTONE,
            assertIs<ThresholdFit.Fit.Unfittable>(ThresholdFit.fit(broken, 90.0)).reason,
        )
        assertTrue(ThresholdFit.isMonotone(milestone2))
    }

    @Test
    fun `a sweep too short to mean anything is refused`() {
        val short = milestone2.take(ThresholdFit.MIN_POINTS - 1)
        assertEquals(
            ThresholdFit.Reason.TOO_FEW_POINTS,
            assertIs<ThresholdFit.Fit.Unfittable>(ThresholdFit.fit(short, 95.0)).reason,
        )
        assertEquals(
            ThresholdFit.Reason.TOO_FEW_POINTS,
            assertIs<ThresholdFit.Fit.Unfittable>(ThresholdFit.fit(emptyList(), 95.0)).reason,
        )
    }

    // ------------------------------------------------------------- rounding

    /**
     * A tenth too high costs a sliver of space per file; a tenth too low costs quality on an
     * app whose claim is that you will not see the difference.
     */
    @Test
    fun `the shipped value rounds away from the risk`() {
        assertEquals(39.8, ThresholdFit.conservative(39.7823))
        assertEquals(39.8, ThresholdFit.conservative(39.71))
        assertEquals(39.8, ThresholdFit.conservative(39.8))
        assertEquals(39.9, ThresholdFit.conservative(39.81))
        assertEquals(40.0, ThresholdFit.conservative(39.782, decimals = 0))
    }

    @Test
    fun `rounding never lowers a threshold`() {
        for (value in listOf(30.0, 33.333, 36.0164, 39.7823, 45.5016)) {
            assertTrue(ThresholdFit.conservative(value) >= value, "$value")
        }
    }

    // ---------------------------------------------------------------- buckets

    /**
     * A resolution nobody measured has no threshold. Borrowing a neighbour's would hide
     * exactly the gap the field test exists to find.
     */
    @Test
    fun `buckets that cannot be fitted are absent, not borrowed`() {
        val sweeps = mapOf(
            "1080p" to milestone2,
            "4K" to milestone2.take(2),
            "720p" to milestone2.map { it.copy(vmaf = it.vmaf - 30) },
        )
        val fitted = ThresholdFit.fitAll(sweeps, targetVmaf = 95.0)
        assertEquals(setOf("1080p"), fitted.keys)
        assertEquals(39.8, fitted["1080p"])
    }

    @Test
    fun `an empty set of sweeps fits nothing without complaining`() {
        assertTrue(ThresholdFit.fitAll(emptyMap<String, List<Point>>(), 95.0).isEmpty())
    }
}
