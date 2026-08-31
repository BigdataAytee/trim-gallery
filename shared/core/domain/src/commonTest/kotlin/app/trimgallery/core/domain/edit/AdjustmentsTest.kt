package app.trimgallery.core.domain.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdjustmentsTest {

    @Test
    fun `an untouched set is neutral and empty`() {
        assertTrue(Adjustments.NONE.isNeutral)
        assertTrue(Adjustments.NONE.moved.isEmpty())
        for (slider in Slider.entries) {
            assertEquals(Adjustments.NEUTRAL, Adjustments.NONE[slider], "$slider")
        }
    }

    /** "Not set" and "set to neutral" are the same thing to the user, so they are here too. */
    @Test
    fun `returning a slider to neutral forgets it`() {
        val moved = Adjustments.NONE.with(Slider.EXPOSURE, 0.4)
        assertFalse(moved.isNeutral)
        assertTrue(moved.with(Slider.EXPOSURE, 0.0).isNeutral)
        assertEquals(Adjustments.NONE, moved.reset(Slider.EXPOSURE))
    }

    @Test
    fun `sliders are clamped to their range`() {
        assertEquals(Adjustments.MAX, Adjustments.NONE.with(Slider.CONTRAST, 5.0)[Slider.CONTRAST])
        assertEquals(Adjustments.MIN, Adjustments.NONE.with(Slider.CONTRAST, -5.0)[Slider.CONTRAST])
    }

    @Test
    fun `equal sets compare equal regardless of how they were built`() {
        val a = Adjustments.of(Slider.EXPOSURE to 0.2, Slider.SATURATION to -0.3)
        val b = Adjustments.NONE
            .with(Slider.SATURATION, -0.3)
            .with(Slider.EXPOSURE, 0.5)
            .with(Slider.EXPOSURE, 0.2)
        assertEquals(a, b)
    }

    // ------------------------------------------------------------- strength

    @Test
    fun `strength zero is exactly the identity`() {
        val vivid = Filter.VIVID.adjustments
        assertTrue(vivid.scaled(0.0).isNeutral)
        assertEquals(Adjustments.NONE, vivid.scaled(0.0))
    }

    @Test
    fun `strength one changes nothing`() {
        val vivid = Filter.VIVID.adjustments
        assertEquals(vivid, vivid.scaled(1.0))
    }

    @Test
    fun `half strength is exactly half of every slider`() {
        val noir = Filter.NOIR.adjustments
        val half = noir.scaled(0.5)
        for (slider in noir.moved) {
            assertEquals(noir[slider] / 2, half[slider], "$slider")
        }
    }

    @Test
    fun `strength outside the range is clamped rather than extrapolated`() {
        val vivid = Filter.VIVID.adjustments
        assertEquals(vivid, vivid.scaled(3.0))
        assertTrue(vivid.scaled(-1.0).isNeutral)
    }

    // ------------------------------------------------------------ combining

    @Test
    fun `a filter and the user's own sliders add`() {
        val filter = Adjustments.of(Slider.WARMTH to 0.3)
        val user = Adjustments.of(Slider.WARMTH to -0.1, Slider.EXPOSURE to 0.2)
        val combined = filter.over(user)
        assertEquals(0.2, combined[Slider.WARMTH], "warmth should land between the two")
        assertEquals(0.2, combined[Slider.EXPOSURE])
    }

    @Test
    fun `adding is symmetric`() {
        val a = Adjustments.of(Slider.CONTRAST to 0.3, Slider.TINT to -0.2)
        val b = Adjustments.of(Slider.CONTRAST to -0.1, Slider.SHADOWS to 0.4)
        assertEquals(a.over(b), b.over(a))
    }

    @Test
    fun `combining with nothing changes nothing`() {
        val a = Adjustments.of(Slider.CONTRAST to 0.3)
        assertEquals(a, a.over(Adjustments.NONE))
        assertEquals(a, Adjustments.NONE.over(a))
    }

    /**
     * Binary floating point has no exact 0.1, so a filter at 0.3 plus a slider at −0.1 lands
     * on 0.19999999999999998 unless the type decides its own resolution. Two edits that
     * should compare equal then do not, and an adjustment the user has cancelled by hand can
     * fail `isNeutral` and write a file for an edit that does nothing.
     */
    @Test
    fun `arithmetic lands on values a user could have set`() {
        val combined = Adjustments.of(Slider.WARMTH to 0.3).over(Adjustments.of(Slider.WARMTH to -0.1))
        assertEquals(Adjustments.of(Slider.WARMTH to 0.2), combined)
        assertEquals(0.2, combined[Slider.WARMTH])

        // Repeated scaling does not drift away from the value it should land on.
        var value = Adjustments.of(Slider.CONTRAST to 0.8)
        repeat(3) { value = value.scaled(0.5) }
        assertEquals(0.1, value[Slider.CONTRAST])
    }

    @Test
    fun `two sliders cancelling each other come back to neutral`() {
        val up = Adjustments.of(Slider.EXPOSURE to 0.4)
        val down = Adjustments.of(Slider.EXPOSURE to -0.4)
        assertTrue(up.over(down).isNeutral)
    }

    /**
     * The clamp makes addition non-associative at the extremes. Documented rather than
     * pretended away: a filter and a slider both pushed to the limit is the user asking for
     * the limit, which is the answer they get.
     */
    @Test
    fun `pushing past the limit stops at the limit`() {
        val a = Adjustments.of(Slider.CONTRAST to 0.8)
        val b = Adjustments.of(Slider.CONTRAST to 0.8)
        assertEquals(Adjustments.MAX, a.over(b)[Slider.CONTRAST])
    }

    // ------------------------------------------------------------- pipeline

    /**
     * A preview that does not match the saved file is worse than no preview, so the order is
     * fixed here rather than in each renderer.
     */
    @Test
    fun `the pipeline covers every slider exactly once`() {
        assertEquals(Slider.entries.size, Adjustments.PIPELINE.size)
        assertEquals(Slider.entries.toSet(), Adjustments.PIPELINE.toSet())
    }

    /** Saturating first and then lifting exposure amplifies the saturation into clipping. */
    @Test
    fun `tone comes before colour`() {
        val lastLight = Adjustments.PIPELINE.indexOfLast { it.isLight }
        val firstColour = Adjustments.PIPELINE.indexOfFirst { !it.isLight }
        assertTrue(lastLight < firstColour, "pipeline interleaves tone and colour: ${Adjustments.PIPELINE}")
    }

    /** Black point is defined against the histogram the earlier tone sliders produced. */
    @Test
    fun `black point is the last of the tone sliders`() {
        assertEquals(Slider.BLACK_POINT, Adjustments.PIPELINE.last { it.isLight })
    }
}
