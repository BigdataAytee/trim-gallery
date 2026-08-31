package app.trimgallery.core.domain.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditRecipeTest {

    private val clipMs = 30_000L

    @Test
    fun `a fresh editor holds no edit`() {
        assertTrue(EditRecipe.NONE.isIdentity())
        assertFalse(EditRecipe.NONE.changesPixels)
        assertEquals("No changes", EditRecipe.NONE.describe())
    }

    /** An edit session the user undid step by step must not write a file. */
    @Test
    fun `four rotations undo themselves`() {
        var recipe = EditRecipe.NONE
        repeat(4) { recipe = recipe.copy(orientation = recipe.orientation.rotatedRight()) }
        assertTrue(recipe.isIdentity())
    }

    @Test
    fun `a filter turned back to nothing is not an edit`() {
        val recipe = EditRecipe.NONE.copy(filter = Filter.VIVID, filterStrength = 0.0)
        assertTrue(recipe.isIdentity())
        assertFalse(recipe.changesPixels)
    }

    @Test
    fun `a trim covering the whole clip is not an edit`() {
        val recipe = EditRecipe.NONE.copy(trim = VideoTrim.full(clipMs))
        assertTrue(recipe.isIdentity(clipMs))
    }

    @Test
    fun `a real trim is an edit`() {
        val recipe = EditRecipe.NONE.copy(trim = VideoTrim(1_000, 5_000))
        assertFalse(recipe.isIdentity(clipMs))
    }

    /**
     * Without the clip's length there is no way to know whether a range covers all of it,
     * and the safe reading of "I don't know" is the one that keeps what the user asked for.
     * The other reading discards a trim silently, on exactly the files whose metadata is
     * already unreliable.
     */
    @Test
    fun `a trim on a clip of unknown length is still an edit`() {
        val recipe = EditRecipe.NONE.copy(trim = VideoTrim(1_000, 5_000))
        assertFalse(recipe.isIdentity(sourceDurationMs = null))
        assertFalse(recipe.isIdentity())
    }

    // ------------------------------------------------------ what it changes

    /**
     * The distinction that makes a rotate free: EXIF, HEIF and MP4 all carry an
     * orientation, so a turn on its own is a tag to write rather than an image to re-encode.
     */
    @Test
    fun `a rotation on its own does not change any pixels`() {
        val turned = EditRecipe.NONE.copy(orientation = Orientation.ROTATE_90)
        assertFalse(turned.changesPixels)
        assertTrue(turned.isOrientationOnly)
        assertFalse(turned.isIdentity())
    }

    @Test
    fun `a rotation with anything else is no longer orientation-only`() {
        val turned = Orientation.ROTATE_90
        assertFalse(EditRecipe.NONE.copy(orientation = turned, filter = Filter.MONO).isOrientationOnly)
        assertFalse(
            EditRecipe.NONE.copy(orientation = turned, crop = CropGeometry.Rect(0.1, 0.1, 0.9, 0.9))
                .isOrientationOnly,
        )
        assertFalse(
            EditRecipe.NONE.copy(orientation = turned, trim = VideoTrim(0, 1_000)).isOrientationOnly,
        )
        assertFalse(EditRecipe.NONE.copy(orientation = turned, straightenDegrees = 2.0).isOrientationOnly)
    }

    @Test
    fun `cropping, straightening and adjusting all change pixels`() {
        assertTrue(EditRecipe.NONE.copy(crop = CropGeometry.Rect(0.1, 0.1, 0.9, 0.9)).changesPixels)
        assertTrue(EditRecipe.NONE.copy(straightenDegrees = 1.5).changesPixels)
        assertTrue(EditRecipe.NONE.copy(filter = Filter.NOIR).changesPixels)
        assertTrue(
            EditRecipe.NONE.copy(adjustments = Adjustments.of(Slider.EXPOSURE to 0.2)).changesPixels,
        )
    }

    // ------------------------------------------------- filter plus sliders

    @Test
    fun `the filter and the sliders combine into one set`() {
        val recipe = EditRecipe.NONE.copy(
            filter = Filter.WARM,
            adjustments = Adjustments.of(Slider.WARMTH to -0.1, Slider.EXPOSURE to 0.25),
        )
        // 0.3 from the filter, 0.1 back from the user, quantised to a value a slider could set.
        assertEquals(0.3, Filter.WARM.adjustments[Slider.WARMTH])
        assertEquals(0.2, recipe.effectiveAdjustments[Slider.WARMTH])
        assertEquals(0.25, recipe.effectiveAdjustments[Slider.EXPOSURE])
    }

    @Test
    fun `a filter's strength scales only the filter, not the user's sliders`() {
        val recipe = EditRecipe.NONE.copy(
            filter = Filter.MONO,
            filterStrength = 0.5,
            adjustments = Adjustments.of(Slider.EXPOSURE to 0.4),
        )
        assertEquals(-0.5, recipe.effectiveAdjustments[Slider.SATURATION])
        assertEquals(0.4, recipe.effectiveAdjustments[Slider.EXPOSURE])
    }

    /** A user can undo a filter's effect by hand, and the app agrees that nothing is left. */
    @Test
    fun `sliders that cancel a filter leave no edit`() {
        val recipe = EditRecipe.NONE.copy(
            filter = Filter.WARM,
            adjustments = Adjustments.of(
                Slider.WARMTH to -Filter.WARM.adjustments[Slider.WARMTH],
                Slider.SATURATION to -Filter.WARM.adjustments[Slider.SATURATION],
                Slider.SHADOWS to -Filter.WARM.adjustments[Slider.SHADOWS],
            ),
        )
        assertTrue(recipe.effectiveAdjustments.isNeutral, "${recipe.effectiveAdjustments}")
        assertTrue(recipe.isIdentity())
    }

    // -------------------------------------------------------------- describe

    @Test
    fun `the info sheet lists what was done`() {
        val recipe = EditRecipe(
            crop = CropGeometry.Rect(0.1, 0.1, 0.9, 0.9),
            orientation = Orientation.ROTATE_90,
            straightenDegrees = 2.0,
            adjustments = Adjustments.of(Slider.EXPOSURE to 0.2),
            filter = Filter.VIVID,
            trim = VideoTrim(0, 5_000),
        )
        assertEquals("Cropped · Straightened · Rotated · Vivid · Adjusted · Trimmed", recipe.describe())
    }

    @Test
    fun `one change reads as one change`() {
        assertEquals("Rotated", EditRecipe.NONE.copy(orientation = Orientation.ROTATE_180).describe())
        assertEquals("Mono", EditRecipe.NONE.copy(filter = Filter.MONO).describe())
    }
}
