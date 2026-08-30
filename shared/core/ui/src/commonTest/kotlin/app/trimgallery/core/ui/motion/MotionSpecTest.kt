package app.trimgallery.core.ui.motion

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
    fun `closing is quicker than opening`() {
        // Opening is a reveal and can luxuriate; closing is a dismissal and must not.
        assertTrue(MotionSpec.Hero.CLOSE_MS < MotionSpec.Hero.OPEN_MS)
    }

    @Test
    fun `only the opening curve overshoots`() {
        assertTrue(MotionSpec.Hero.OPEN_EASING.y2 > 1f, "open should overshoot")
        assertTrue(MotionSpec.Hero.CLOSE_EASING.y2 <= 1f, "close should not overshoot")
    }

    @Test
    fun `the sheet starts after the zoom but finishes with it in view`() {
        assertTrue(MotionSpec.Sheet.DELAY_MS > 0, "the image should lead")
        assertTrue(
            MotionSpec.Sheet.DELAY_MS < MotionSpec.Hero.OPEN_MS,
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
