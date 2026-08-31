package app.trimgallery.core.domain.edit

import app.trimgallery.core.domain.edit.CropGeometry.AspectLock
import app.trimgallery.core.domain.edit.CropGeometry.Handle
import app.trimgallery.core.domain.edit.CropGeometry.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropGeometryTest {

    /** A 4000×3000 source, the shape most phone photographs are. */
    private val fourThree = 4.0 / 3.0
    private val sixteenNine = 16.0 / 9.0

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9, message: String = "") {
        assertTrue(abs(expected - actual) <= tolerance, "$message expected $expected, was $actual")
    }

    // ---------------------------------------------------------------- shape

    /**
     * A normalised rectangle's shape is not its numbers. On a 4:3 source, a normalised
     * square is 4:3 — the mistake this parameter exists to prevent.
     */
    @Test
    fun `a normalised square is not a square crop`() {
        assertClose(fourThree, Rect.FULL.aspect(fourThree))
        assertClose(fourThree, Rect(0.25, 0.25, 0.75, 0.75).aspect(fourThree))
        assertClose(1.0, Rect(0.0, 0.0, 0.75, 1.0).aspect(fourThree))
    }

    @Test
    fun `the presets cover the shapes the editor offers, both ways up`() {
        val fixed = CropGeometry.PRESETS.filterIsInstance<AspectLock.Fixed>()
        assertTrue(AspectLock.Fixed(1, 1) in fixed)
        for (landscape in listOf(4 to 3, 16 to 9, 3 to 2)) {
            assertTrue(AspectLock.Fixed(landscape.first, landscape.second) in fixed, "$landscape")
            assertTrue(AspectLock.Fixed(landscape.second, landscape.first) in fixed, "$landscape flipped")
        }
    }

    @Test
    fun `the original lock follows the source and free locks nothing`() {
        assertEquals(fourThree, AspectLock.Original.ratioFor(fourThree))
        assertEquals(null, AspectLock.Free.ratioFor(fourThree))
        assertEquals(1.0, AspectLock.Fixed(1, 1).ratioFor(fourThree))
    }

    // --------------------------------------------------------------- clamp

    @Test
    fun `clamping slides a rectangle back inside without resizing it`() {
        val clamped = CropGeometry.clamp(Rect(0.8, 0.8, 1.3, 1.3))
        assertClose(0.5, clamped.width)
        assertClose(0.5, clamped.height)
        assertClose(1.0, clamped.right)
        assertClose(1.0, clamped.bottom)
    }

    @Test
    fun `a rectangle bigger than the picture is cut down to it`() {
        val clamped = CropGeometry.clamp(Rect(-0.5, -0.5, 1.5, 1.5))
        assertEquals(Rect.FULL, clamped)
    }

    // ---------------------------------------------------------------- drag

    @Test
    fun `dragging a corner leaves the opposite corner alone`() {
        val dragged = CropGeometry.drag(Rect.FULL, Handle.TOP_LEFT, dx = 0.2, dy = 0.1)
        assertClose(0.2, dragged.left)
        assertClose(0.1, dragged.top)
        assertClose(1.0, dragged.right)
        assertClose(1.0, dragged.bottom)
    }

    @Test
    fun `dragging one side moves only that side`() {
        val dragged = CropGeometry.drag(Rect.FULL, Handle.RIGHT, dx = -0.3, dy = 0.4)
        assertEquals(Rect(0.0, 0.0, 0.7, 1.0), dragged)
    }

    /** A gesture that stops responding reads as a bug even when refusing is correct. */
    @Test
    fun `a drag past the edge stops at the edge rather than being refused`() {
        val dragged = CropGeometry.drag(Rect.FULL, Handle.LEFT, dx = -0.5, dy = 0.0)
        assertClose(0.0, dragged.left)
        assertClose(1.0, dragged.right)
    }

    @Test
    fun `a crop cannot be dragged smaller than the minimum`() {
        val dragged = CropGeometry.drag(Rect.FULL, Handle.RIGHT, dx = -0.99, dy = 0.0)
        assertClose(CropGeometry.MIN_SIDE, dragged.width)
    }

    @Test
    fun `the minimum is enforced against the anchored edge, not by moving it`() {
        val start = Rect(0.4, 0.0, 0.9, 1.0)
        val dragged = CropGeometry.drag(start, Handle.LEFT, dx = 0.9)
        assertClose(0.9, dragged.right, message = "the anchored edge moved:")
        assertClose(CropGeometry.MIN_SIDE, dragged.width)
    }

    // ----------------------------------------------------------- aspect lock

    @Test
    fun `a locked drag keeps its shape exactly`() {
        val square = AspectLock.Fixed(1, 1)
        val dragged = CropGeometry.drag(Rect.FULL, Handle.RIGHT, dx = -0.4, lock = square, sourceAspect = fourThree)
        assertClose(1.0, dragged.aspect(fourThree), tolerance = 1e-9)
    }

    @Test
    fun `a locked corner drag anchors the opposite corner on both axes`() {
        val lock = AspectLock.Fixed(1, 1)
        val dragged =
            CropGeometry.drag(Rect.FULL, Handle.TOP_LEFT, dx = 0.4, dy = 0.0, lock = lock, sourceAspect = fourThree)
        assertClose(1.0, dragged.right)
        assertClose(1.0, dragged.bottom)
        assertClose(1.0, dragged.aspect(fourThree))
    }

    /**
     * The failure a per-edge clamp would cause: dragging a locked crop past the picture's
     * edge would crop one side to the boundary and silently leave the lock behind.
     */
    @Test
    fun `a locked drag that overflows keeps the lock rather than the position`() {
        val lock = AspectLock.Fixed(16, 9)
        val start = Rect(0.0, 0.4, 0.3, 0.7)
        val dragged = CropGeometry.drag(start, Handle.RIGHT, dx = 5.0, lock = lock, sourceAspect = fourThree)
        assertClose(sixteenNine, dragged.aspect(fourThree), tolerance = 1e-9)
        assertTrue(dragged.left >= -1e-9 && dragged.right <= 1.0 + 1e-9, "outside: $dragged")
        assertTrue(dragged.top >= -1e-9 && dragged.bottom <= 1.0 + 1e-9, "outside: $dragged")
    }

    @Test
    fun `dragging a side under a lock grows the other axis from the centre`() {
        val lock = AspectLock.Fixed(1, 1)
        val start = Rect(0.2, 0.2, 0.4, 0.4)
        val dragged = CropGeometry.drag(start, Handle.BOTTOM, dy = 0.2, lock = lock, sourceAspect = 1.0)
        assertClose(0.3, dragged.centerX, message = "the frame walked sideways:")
        assertClose(1.0, dragged.aspect(1.0))
    }

    // ----------------------------------------------------------- straighten

    @Test
    fun `no rotation needs no zoom`() {
        assertClose(1.0, CropGeometry.maxCentredWidth(fourThree, 0.0, fourThree))
        assertEquals(Rect.FULL, CropGeometry.straightenedCrop(fourThree, 0.0, fourThree))
    }

    @Test
    fun `straightening always costs something`() {
        for (angle in listOf(0.5, 1.0, 3.0, 7.0, CropGeometry.MAX_STRAIGHTEN_DEGREES)) {
            val width = CropGeometry.maxCentredWidth(fourThree, angle, fourThree)
            assertTrue(width < 1.0, "$angle° kept the whole width")
            assertTrue(width > 0.5, "$angle° zoomed absurdly far in: $width")
        }
    }

    @Test
    fun `the direction of the tilt does not matter`() {
        for (angle in listOf(1.0, 5.0, 12.0)) {
            assertClose(
                CropGeometry.maxCentredWidth(fourThree, angle, fourThree),
                CropGeometry.maxCentredWidth(fourThree, -angle, fourThree),
                tolerance = 1e-12,
            )
        }
    }

    @Test
    fun `more tilt needs more zoom`() {
        var previous = 1.1
        for (angle in listOf(0.0, 1.0, 2.0, 5.0, 10.0, 15.0)) {
            val width = CropGeometry.maxCentredWidth(fourThree, angle, fourThree)
            assertTrue(width < previous, "$angle° did not shrink further: $width")
            previous = width
        }
    }

    /**
     * The property the whole calculation is for: the crop, rotated back by the straighten
     * angle, has to fit inside the picture. If it does not, the user sees grey corners.
     */
    @Test
    fun `the straightened crop fits inside the rotated picture`() {
        for (sourceAspect in listOf(1.0, fourThree, sixteenNine, 0.75)) {
            for (aspect in listOf(1.0, fourThree, sixteenNine, 2.0 / 3.0)) {
                for (angle in listOf(0.0, 1.0, 4.0, 9.0, 15.0)) {
                    val crop = CropGeometry.straightenedCrop(sourceAspect, angle, aspect)
                    val radians = angle * 3.141592653589793 / 180
                    // Back to pixels, with the source normalised to height 1.
                    val w = crop.width * sourceAspect
                    val h = crop.height
                    val boundsW = abs(w * cos(radians)) + abs(h * sin(radians))
                    val boundsH = abs(w * sin(radians)) + abs(h * cos(radians))
                    val where = "source $sourceAspect, crop $aspect, $angle°"
                    assertTrue(boundsW <= sourceAspect + 1e-9, "$where: too wide by ${boundsW - sourceAspect}")
                    assertTrue(boundsH <= 1.0 + 1e-9, "$where: too tall by ${boundsH - 1.0}")
                }
            }
        }
    }

    /** And it has to be the *largest* such crop, or the editor zooms in further than it must. */
    @Test
    fun `the straightened crop is as large as it can be`() {
        for (angle in listOf(1.0, 5.0, 15.0)) {
            val crop = CropGeometry.straightenedCrop(fourThree, angle, fourThree)
            val radians = angle * 3.141592653589793 / 180
            val w = crop.width * fourThree * 1.001
            val h = crop.height * 1.001
            val boundsW = abs(w * cos(radians)) + abs(h * sin(radians))
            val boundsH = abs(w * sin(radians)) + abs(h * cos(radians))
            assertTrue(
                boundsW > fourThree + 1e-12 || boundsH > 1.0 + 1e-12,
                "$angle°: a crop 0.1% larger would still have fitted",
            )
        }
    }

    @Test
    fun `the straightened crop is centred and keeps its shape`() {
        val crop = CropGeometry.straightenedCrop(sixteenNine, 8.0, 1.0)
        assertClose(0.5, crop.centerX)
        assertClose(0.5, crop.centerY)
        assertClose(1.0, crop.aspect(sixteenNine), tolerance = 1e-9)
    }

    // -------------------------------------------------------------- output

    @Test
    fun `the kept fraction is what the sheet reports`() {
        assertClose(1.0, CropGeometry.keptFraction(Rect.FULL))
        assertClose(0.25, CropGeometry.keptFraction(Rect(0.25, 0.25, 0.75, 0.75)))
    }

    /** Encoders reject odd dimensions, so the crop can never produce one. */
    @Test
    fun `output dimensions are always even`() {
        for (rect in listOf(Rect.FULL, Rect(0.0, 0.0, 0.333, 0.777), Rect(0.1, 0.1, 0.1001, 0.1001))) {
            val (w, h) = CropGeometry.outputSize(4000, 3000, rect, Orientation.NORMAL)
            assertEquals(0, w % 2, "width $w")
            assertEquals(0, h % 2, "height $h")
            assertTrue(w >= 2 && h >= 2, "$w x $h")
        }
    }

    @Test
    fun `a quarter turn swaps the output dimensions`() {
        assertEquals(4000 to 3000, CropGeometry.outputSize(4000, 3000, Rect.FULL, Orientation.NORMAL))
        assertEquals(3000 to 4000, CropGeometry.outputSize(4000, 3000, Rect.FULL, Orientation.ROTATE_90))
        assertEquals(4000 to 3000, CropGeometry.outputSize(4000, 3000, Rect.FULL, Orientation.ROTATE_180))
    }
}
