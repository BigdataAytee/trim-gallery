package app.trimgallery.core.ui.motion

import app.trimgallery.core.ui.theme.ReducedMotion
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimSpring
import app.trimgallery.core.ui.theme.TrimType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionSpecTest {

    @Test
    fun `arrival stagger matches the reference`() {
        // Reference: (index % 6) * 70ms.
        assertEquals(0, MotionSpec.Arrival.delayMs(0))
        assertEquals(70, MotionSpec.Arrival.delayMs(1))
        assertEquals(350, MotionSpec.Arrival.delayMs(5))
        assertEquals(0, MotionSpec.Arrival.delayMs(6))
        assertEquals(140, MotionSpec.Arrival.delayMs(20))
    }

    @Test
    fun `stagger never accumulates beyond one group`() {
        val worst = (0..999).maxOf { MotionSpec.Arrival.delayMs(it) }
        assertEquals((MotionSpec.Arrival.STAGGER_GROUP - 1) * MotionSpec.Arrival.STAGGER_MS, worst)
        assertTrue(worst < MotionSpec.Arrival.DURATION_MS, "a tile should never wait longer than the animation")
    }

    @Test
    fun `the dismiss drag follows the finger exactly`() {
        // Rubber-banding an image the user is dragging makes it feel like a proxy for the
        // photo rather than the photo (DESIGN_SYSTEM.md, `dismiss`).
        assertEquals(1f, MotionSpec.Dismiss.DRAG_RATIO)
        // Chrome is gone before the image lands, not arriving with it.
        assertTrue(MotionSpec.Dismiss.CHROME_FADE_MS < ReducedMotion.DURATION_MS)
    }

    @Test
    fun `every spring overshoots once and settles`() {
        // Critically damped (ratio 1.0) is indistinguishable from an ease-out, which
        // gives up the reason for using a spring; below about 0.6 a second bounce becomes
        // visible and reads as a glitch on a gallery.
        TrimSpring.entries.forEach { spring ->
            assertTrue(
                spring.dampingRatio in 0.6f..0.95f,
                "${spring.name} damping ratio is ${spring.dampingRatio}",
            )
        }
    }

    @Test
    fun `snappy is faster than standard, which is faster than gentle`() {
        assertTrue(TrimSpring.SNAPPY.stiffness > TrimSpring.STANDARD.stiffness)
        assertTrue(TrimSpring.STANDARD.stiffness > TrimSpring.GENTLE.stiffness)
    }

    @Test
    fun `reduce-motion drops count-ups entirely`() {
        // A number counting up is decoration, not information; the figure it lands on is
        // the information, and reduce-motion should show it immediately.
        assertTrue(!ReducedMotion.COUNT_UP_ENABLED)
        assertTrue(MotionSpec.ResultCard.COUNT_UP_MS > ReducedMotion.DURATION_MS)
    }

    @Test
    fun `the type scale steps upward without collision`() {
        val roles = TrimType.entries
        roles.zipWithNext().forEach { (bigger, smaller) ->
            assertTrue(bigger.size > smaller.size, "${bigger.name} must be larger than ${smaller.name}")
            assertTrue(bigger.lineHeight > smaller.lineHeight)
        }
        // Every line height leaves room for descenders at its own size.
        roles.forEach { assertTrue(it.lineHeight > it.size, "${it.name} line height crowds its glyphs") }
    }

    @Test
    fun `every control clears the minimum touch target`() {
        // DESIGN_SYSTEM.md § Accessibility: all controls at least 48 dp.
        assertEquals(48f, TrimSpacing.MIN_TOUCH_TARGET_DP)
        assertTrue(TrimSpacing.MIN_TOUCH_TARGET_DP % TrimSpacing.GRID_DP == 0f, "off the 4-pt grid")
    }

    @Test
    fun `radii sit on the 4-pt grid, and a chip is a pill`() {
        listOf(TrimShape.THUMBNAIL_DP, TrimShape.CARD_DP, TrimShape.SHEET_DP, TrimShape.BUTTON_DP)
            .forEach { assertTrue(it % TrimSpacing.GRID_DP == 0f, "$it is off the 4-pt grid") }
        assertTrue(TrimShape.CHIP_DP >= 999f)
    }

    @Test
    fun `the sheet starts after the opening move but finishes with it in view`() {
        assertTrue(MotionSpec.Sheet.DELAY_MS > 0, "the image should lead")
        assertTrue(
            MotionSpec.Sheet.DELAY_MS < ReducedMotion.DURATION_MS,
            "the sheet should start while the zoom is still running, not after it",
        )
    }

    @Test
    fun `autoplay threshold is half the tile`() {
        assertEquals(0.5f, MotionSpec.AUTOPLAY_VISIBLE_FRACTION)
    }

    @Test
    fun `the 120Hz frame budget is what BUILD md section 2 point 7 requires`() {
        assertTrue(MotionSpec.FRAME_BUDGET_MS_120HZ in 8.3f..8.4f, "${MotionSpec.FRAME_BUDGET_MS_120HZ}")
    }

    @Test
    fun `press feedback is a nudge, not a squash`() {
        assertTrue(MotionSpec.Press.SCALE in 0.9f..0.99f)
        assertTrue(MotionSpec.Press.DURATION_MS <= 150, "press feedback must feel immediate")
    }
}

class TilePhaseTest {

    @Test
    fun `the offset is stable for a given id`() {
        repeat(5) { assertEquals(TilePhase.offsetMs("media-42"), TilePhase.offsetMs("media-42")) }
    }

    @Test
    fun `the offset always lands inside the cycle`() {
        (0..500).forEach { i ->
            val offset = TilePhase.offsetMs("item-$i")
            assertTrue(offset in 0 until MotionSpec.Breathing.PERIOD_MS, "id=item-$i offset=$offset")
        }
    }

    @Test
    fun `neighbouring ids do not land on the same phase`() {
        val phases = (0..40).map { TilePhase.offsetMs("item-$it") }
        assertEquals(phases.size, phases.toSet().size, "collision among sequential ids: $phases")
    }

    @Test
    fun `phases spread across the cycle rather than clustering`() {
        // Four quarters of the cycle should all be occupied across a screenful of tiles.
        val quarters = (0..60).map { TilePhase.fraction("item-$it") }
            .map { (it * 4).toInt().coerceAtMost(3) }
            .toSet()
        assertEquals(setOf(0, 1, 2, 3), quarters, "phases clustered into $quarters")
    }

    @Test
    fun `fraction is the offset expressed over the period`() {
        val id = "abc"
        assertEquals(
            TilePhase.offsetMs(id).toFloat() / MotionSpec.Breathing.PERIOD_MS,
            TilePhase.fraction(id),
        )
    }

    @Test
    fun `an empty id still yields a usable offset`() {
        // The requirement is that it does not crash and stays inside the cycle; which
        // particular offset it lands on is an implementation detail.
        val offset = TilePhase.offsetMs("")
        assertTrue(offset in 0 until MotionSpec.Breathing.PERIOD_MS, "offset=$offset")
    }

    @Test
    fun `a one-character difference moves the phase a long way on average`() {
        // The point of the avalanche. A plain `hash * 31 + char` gave sequential ids
        // phases ~31ms apart out of 4600 — visually identical, so the offset achieved
        // nothing. Well-spread phases average a gap of about a third of the cycle; any
        // individual pair may still land close, which is what randomness looks like, so
        // this asserts the average rather than every pair.
        val gaps = (0..200).map { TilePhase.offsetMs("item-$it") }
            .zipWithNext { a, b -> kotlin.math.abs(a - b) }
        val mean = gaps.average()
        assertTrue(
            mean > MotionSpec.Breathing.PERIOD_MS / 5.0,
            "mean gap between sequential ids was $mean ms of ${MotionSpec.Breathing.PERIOD_MS}",
        )
    }
}
