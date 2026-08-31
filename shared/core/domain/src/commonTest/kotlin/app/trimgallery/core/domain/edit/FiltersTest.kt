package app.trimgallery.core.domain.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FiltersTest {

    @Test
    fun `the original is the identity`() {
        assertTrue(Filter.NONE.adjustments.isNeutral)
        assertTrue(Filter.NONE.at(1.0).isNeutral)
    }

    @Test
    fun `every other filter does something`() {
        for (filter in Filter.entries - Filter.NONE) {
            assertFalse(filter.at(Filter.FULL_STRENGTH).isNeutral, "${filter.label} is a no-op")
        }
    }

    @Test
    fun `every filter has a label a user could read`() {
        for (filter in Filter.entries) {
            assertTrue(filter.label.isNotBlank(), "$filter")
            assertEquals(filter.label.trim(), filter.label, "$filter")
        }
    }

    /** A filter must be expressible on the sliders, or the sliders and the filter would fight. */
    @Test
    fun `every filter stays inside the slider ranges`() {
        for (filter in Filter.entries) {
            for (slider in filter.adjustments.moved) {
                val value = filter.adjustments[slider]
                assertTrue(value in Adjustments.MIN..Adjustments.MAX, "${filter.label} $slider = $value")
            }
        }
    }

    @Test
    fun `strength zero leaves the picture alone`() {
        for (filter in Filter.entries) {
            assertTrue(filter.at(0.0).isNeutral, "${filter.label}")
        }
    }

    /**
     * Half a monochrome filter is a half-desaturated picture, not a translucent
     * black-and-white one. That is the version the sliders can express, and the version the
     * user can keep adjusting from.
     */
    @Test
    fun `half a monochrome filter is half desaturated`() {
        assertEquals(-0.5, Filter.MONO.at(0.5)[Slider.SATURATION])
        assertEquals(-1.0, Filter.MONO.at(1.0)[Slider.SATURATION])
    }

    @Test
    fun `the monochrome filters take saturation to the floor`() {
        for (filter in listOf(Filter.MONO, Filter.NOIR)) {
            assertEquals(Adjustments.MIN, filter.adjustments[Slider.SATURATION], "${filter.label}")
        }
    }

    @Test
    fun `the warm and cool filters disagree about warmth`() {
        assertTrue(Filter.WARM.adjustments[Slider.WARMTH] > 0)
        assertTrue(Filter.COOL.adjustments[Slider.WARMTH] < 0)
    }

    /** Dramatic without contrast, or Vivid without saturation, would be a mislabelled button. */
    @Test
    fun `the filters do what their names say`() {
        assertTrue(Filter.VIVID.adjustments[Slider.SATURATION] > 0)
        assertTrue(Filter.DRAMATIC.adjustments[Slider.CONTRAST] > Filter.VIVID.adjustments[Slider.CONTRAST])
        assertTrue(Filter.NOIR.adjustments[Slider.CONTRAST] > Filter.MONO.adjustments[Slider.CONTRAST])
    }

    @Test
    fun `a filter is a small enough set of sliders to still be adjustable`() {
        for (filter in Filter.entries) {
            assertTrue(filter.adjustments.moved.size <= 4, "${filter.label} moves too much to adjust from")
        }
    }
}
