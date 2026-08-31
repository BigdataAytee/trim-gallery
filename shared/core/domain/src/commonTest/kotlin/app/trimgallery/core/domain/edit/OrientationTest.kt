package app.trimgallery.core.domain.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrientationTest {

    @Test
    fun `four rotations come back to the start, from anywhere`() {
        for (start in Orientation.entries) {
            var o = start
            repeat(4) { o = o.rotatedRight() }
            assertEquals(start, o, "$start")
        }
    }

    @Test
    fun `left undoes right`() {
        for (start in Orientation.entries) {
            assertEquals(start, start.rotatedRight().rotatedLeft(), "$start")
            assertEquals(start, start.rotatedLeft().rotatedRight(), "$start")
        }
    }

    @Test
    fun `every flip is its own undo`() {
        for (start in Orientation.entries) {
            assertEquals(start, start.flippedHorizontally().flippedHorizontally(), "$start")
            assertEquals(start, start.flippedVertically().flippedVertically(), "$start")
        }
    }

    /**
     * The bug this class exists to prevent: with a rotation field and a separate mirror
     * flag, "flip horizontally" after a quarter-turn flips the wrong axis, because by then
     * the screen's horizontal is the file's vertical.
     */
    @Test
    fun `flipping horizontally twice is identity even after a quarter turn`() {
        val turned = Orientation.NORMAL.rotatedRight()
        assertEquals(turned, turned.flippedHorizontally().flippedHorizontally())
        assertEquals(
            Orientation.NORMAL.flippedHorizontally().rotatedRight(),
            Orientation.NORMAL.rotatedRight().flippedVertically(),
        )
    }

    /**
     * Mirroring reverses the sense of a rotation — mirror, turn right, mirror back, and the
     * picture has turned *left*. That conjugation is what makes this a group that cannot be
     * modelled as "add the turns, exclusive-or the flags", and it is why the order of the
     * user's taps changes the answer.
     */
    @Test
    fun `mirroring reverses the sense of a rotation`() {
        assertEquals(
            Orientation.ROTATE_270,
            Orientation.MIRROR.then(Orientation.ROTATE_90).then(Orientation.MIRROR),
        )
        assertEquals(
            Orientation.ROTATE_90,
            Orientation.MIRROR.then(Orientation.ROTATE_270).then(Orientation.MIRROR),
        )
    }

    @Test
    fun `turning then flipping is not the same as flipping then turning`() {
        assertEquals(Orientation.MIRROR_ROTATE_270, Orientation.NORMAL.rotatedRight().flippedHorizontally())
        assertEquals(Orientation.MIRROR_ROTATE_90, Orientation.NORMAL.flippedHorizontally().rotatedRight())
    }

    @Test
    fun `composition is associative and has an identity`() {
        for (a in Orientation.entries) {
            assertEquals(a, a.then(Orientation.NORMAL), "$a")
            assertEquals(a, Orientation.NORMAL.then(a), "$a")
            for (b in Orientation.entries) {
                for (c in Orientation.entries) {
                    assertEquals(a.then(b).then(c), a.then(b.then(c)), "$a $b $c")
                }
            }
        }
    }

    @Test
    fun `every orientation has an inverse`() {
        for (a in Orientation.entries) {
            assertTrue(a.then(a.inverse()).isIdentity, "$a")
            assertTrue(a.inverse().then(a).isIdentity, "$a")
        }
    }

    @Test
    fun `only quarter turns swap the dimensions`() {
        assertFalse(Orientation.NORMAL.swapsDimensions)
        assertFalse(Orientation.ROTATE_180.swapsDimensions)
        assertFalse(Orientation.MIRROR.swapsDimensions)
        assertTrue(Orientation.ROTATE_90.swapsDimensions)
        assertTrue(Orientation.ROTATE_270.swapsDimensions)
        assertTrue(Orientation.MIRROR_ROTATE_90.swapsDimensions)
    }

    /**
     * EXIF 5 and 7 are transpose and transverse — a mirror *and* a turn. Getting either
     * backwards turns a minority of photographs upside down, which is the classic "only
     * pictures from one phone, held one way, are wrong" bug.
     */
    @Test
    fun `the EXIF tag round-trips for all eight values`() {
        for (value in 1..8) {
            assertEquals(value, Orientation.fromExif(value).exif, "exif $value")
        }
        for (orientation in Orientation.entries) {
            assertEquals(orientation, Orientation.fromExif(orientation.exif), "$orientation")
        }
    }

    @Test
    fun `the two common camera values are the ones users see most`() {
        assertEquals(Orientation.NORMAL, Orientation.fromExif(1))
        assertEquals(Orientation.ROTATE_90, Orientation.fromExif(6))
        assertEquals(Orientation.ROTATE_270, Orientation.fromExif(8))
        assertEquals(Orientation.ROTATE_180, Orientation.fromExif(3))
    }

    /** Cameras write 0 often enough that refusing it would mean refusing real photographs. */
    @Test
    fun `nonsense in the EXIF tag is treated as the right way up`() {
        for (bad in listOf(0, -1, 9, 255)) {
            assertEquals(Orientation.NORMAL, Orientation.fromExif(bad), "$bad")
        }
    }
}
