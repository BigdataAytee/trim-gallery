package app.trimgallery.core.ui.motion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The expected numbers here were measured in a browser from the signed-off reference
 * prototype (`design/buyer-gallery/tests/acceptance.mjs`, item 5), not derived from this
 * implementation. That is what makes them a check on the port rather than a restatement
 * of it.
 */
class HeroGeometryTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.5f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected ~$expected, was $actual")

    @Test
    fun `target on a 390dp window matches the measured reference`() {
        // Browser, 390x844: hero measured at x=18, y=172, 354x354.
        val r = HeroGeometry.target(390f, 844f)
        assertClose(354f, r.width)
        assertClose(354f, r.height)
        assertClose(18f, r.left)
        assertClose(172f, r.top)
    }

    @Test
    fun `target on a 430dp window matches the measured reference`() {
        // Browser, 430x844: width capped by the shell, 430 - 36 = 394.
        val r = HeroGeometry.target(430f, 844f)
        assertClose(394f, r.width)
        assertClose(18f, r.left)
    }

    @Test
    fun `width is capped by the shell on a wide window`() {
        val r = HeroGeometry.target(1200f, 900f)
        assertClose(HeroGeometry.SHELL_MAX_DP - HeroGeometry.SIDE_INSET_DP, r.width)
    }

    @Test
    fun `the square is always centred horizontally`() {
        listOf(360f, 390f, 412f, 430f, 768f).forEach { w ->
            val r = HeroGeometry.target(w, 844f)
            assertClose(w / 2f, r.centerX, tolerance = 0.01f)
        }
    }

    @Test
    fun `it never tucks under the status bar on a short window`() {
        // A landscape phone leaves almost no vertical room; the bias must not win.
        val r = HeroGeometry.target(740f, 360f)
        assertTrue(r.top >= HeroGeometry.MIN_TOP_DP, "top was ${r.top}")
    }

    @Test
    fun `it sits above centre so the sheet has room`() {
        val r = HeroGeometry.target(390f, 844f)
        val gapBelow = 844f - r.bottom
        val gapAbove = r.top
        assertTrue(gapBelow > gapAbove, "above=$gapAbove below=$gapBelow")
    }

    @Test
    fun `lerp starts at the tile and ends at the target`() {
        val tile = HeroGeometry.Rect(18f, 194f, 170f, 170f)
        val target = HeroGeometry.target(390f, 844f)
        assertEquals(tile, HeroGeometry.lerp(tile, target, 0f))
        assertEquals(target, HeroGeometry.lerp(tile, target, 1f))
    }

    @Test
    fun `lerp is continuous through the middle`() {
        val tile = HeroGeometry.Rect(18f, 194f, 170f, 170f)
        val target = HeroGeometry.target(390f, 844f)
        val mid = HeroGeometry.lerp(tile, target, 0.5f)
        assertClose((tile.width + target.width) / 2f, mid.width)
        assertClose((tile.top + target.top) / 2f, mid.top)
    }

    @Test
    fun `an overshoot easing past 1 stays a coherent rectangle`() {
        // The open curve (0.2, 0.9, 0.25, 1.1) exceeds 1 before settling.
        val tile = HeroGeometry.Rect(18f, 194f, 170f, 170f)
        val target = HeroGeometry.target(390f, 844f)
        val over = HeroGeometry.lerp(tile, target, 1.06f)
        assertTrue(over.width > target.width, "overshoot should exceed the target")
        assertTrue(over.width > 0f && over.height > 0f)
        assertClose(over.width, over.height) // still square
    }

    @Test
    fun `corner radius opens from the thumbnail to a square corner`() {
        // DESIGN_SYSTEM.md, `shared-element`: radius 4 to 0. The tile is a rounded
        // thumbnail in a grid; the viewer is full-bleed and has no corners to round.
        assertClose(MotionSpec.Hero.TILE_RADIUS_DP, HeroGeometry.lerpRadius(0f))
        assertClose(MotionSpec.Hero.HERO_RADIUS_DP, HeroGeometry.lerpRadius(1f))
        assertClose(2f, HeroGeometry.lerpRadius(0.5f))
    }

    @Test
    fun `drag dismissal reaches full progress at half the window height`() {
        assertClose(0f, HeroGeometry.dismissProgress(0f, 844f))
        assertClose(0.5f, HeroGeometry.dismissProgress(211f, 844f), tolerance = 0.01f)
        assertClose(1f, HeroGeometry.dismissProgress(422f, 844f), tolerance = 0.01f)
    }

    @Test
    fun `dragging up dismisses as readily as dragging down`() {
        assertEquals(
            HeroGeometry.dismissProgress(120f, 844f),
            HeroGeometry.dismissProgress(-120f, 844f),
        )
    }

    @Test
    fun `dismiss progress is clamped rather than running away`() {
        assertClose(1f, HeroGeometry.dismissProgress(5000f, 844f))
    }

    @Test
    fun `the image shrinks as it is dragged away, and never inverts`() {
        assertClose(1f, HeroGeometry.dismissScale(0f))
        assertTrue(HeroGeometry.dismissScale(1f) in 0.5f..1f)
        assertTrue(HeroGeometry.dismissScale(0.5f) > HeroGeometry.dismissScale(1f))
    }
}
