package app.trimgallery.core.pipeline.index

import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The hash decides which of a user's photos they are offered the chance to delete, so the
 * properties it must have are the ones tested here — not the bits it happens to produce.
 */
class PerceptualHashTest {

    /**
     * A deterministic "photograph": smooth structure plus fine detail.
     *
     * The seed changes the *frequencies*, not just the phase. An earlier version only
     * shifted phase, and the test that unrelated images sit far apart failed at two bits —
     * correctly, because two phase-shifted copies of one pattern really are the same
     * picture to a perceptual hash. The fixture was wrong, not the hash.
     */
    private fun photo(width: Int, height: Int, seed: Int = 1, brightness: Int = 0): ByteArray {
        val random = Random(seed)
        val fx = 2.0 + seed * 1.7
        val fy = 3.0 + seed * 2.3
        val diagonal = 1.0 + seed * 0.9
        return ByteArray(width * height) { i ->
            val x = (i % width).toDouble() / width
            val y = (i / width).toDouble() / height
            val structure = 128 +
                60 * sin(x * fx + seed) * sin(y * fy + seed * 2) +
                30 * sin((x + y) * diagonal * 4.0)
            val grain = random.nextInt(-8, 9)
            (structure + grain + brightness).coerceIn(0.0, 255.0).toInt().toByte()
        }
    }

    private fun hash(width: Int, height: Int, seed: Int = 1, brightness: Int = 0) =
        PerceptualHash.of(photo(width, height, seed, brightness), width, height)

    @Test
    fun `the same image always hashes the same`() {
        // Two devices must agree, or a user's duplicate groups dissolve when their library
        // moves. This is the same requirement seen from one machine.
        assertEquals(hash(640, 480), hash(640, 480))
    }

    @Test
    fun `unrelated images are far apart`() {
        val distances = (2..12).map { seed -> PerceptualHash.distance(hash(640, 480), hash(640, 480, seed)) }
        distances.forEach {
            assertTrue(it > PerceptualHash.NEAR_DUPLICATE_DISTANCE, "unrelated images only $it bits apart")
        }
    }

    @Test
    fun `a resized copy still matches its original`() {
        // "Edited copies" in BUILD.md § 8 are usually a resize. The box reduction is what
        // makes this hold; nearest-neighbour sampling would make the hash depend on the
        // source resolution.
        val full = hash(1600, 1200)
        listOf(800 to 600, 400 to 300, 320 to 240).forEach { (w, h) ->
            val distance = PerceptualHash.distance(full, hash(w, h))
            assertTrue(
                PerceptualHash.isNearDuplicate(full, hash(w, h)),
                "${w}x$h is $distance bits from the original",
            )
        }
    }

    @Test
    fun `a brightness shift does not break the match`() {
        // Excluding the DC coefficient and thresholding on the median is what buys this:
        // a uniformly brighter copy has the same structure.
        val original = hash(640, 480)
        listOf(-30, -15, 15, 30).forEach { delta ->
            val shifted = hash(640, 480, brightness = delta)
            assertTrue(
                PerceptualHash.isNearDuplicate(original, shifted),
                "a shift of $delta moved the hash ${PerceptualHash.distance(original, shifted)} bits",
            )
        }
    }

    @Test
    fun `re-compression noise does not break the match`() {
        // The realistic near-duplicate: the same photo sent through a messaging app.
        val width = 640
        val height = 480
        val original = photo(width, height)
        val recompressed = ByteArray(original.size) { i ->
            val noise = Random(99 + i).nextInt(-6, 7)
            ((original[i].toInt() and 0xFF) + noise).coerceIn(0, 255).toByte()
        }
        val distance = PerceptualHash.distance(
            PerceptualHash.of(original, width, height),
            PerceptualHash.of(recompressed, width, height),
        )
        assertTrue(distance <= PerceptualHash.NEAR_DUPLICATE_DISTANCE, "noise moved it $distance bits")
    }

    @Test
    fun `a flat image does not collide with every other flat image`() {
        // A mean-thresholded hash puts every dim photograph in one bucket. Two different
        // near-uniform images should still be told apart by their faint structure.
        val grey = ByteArray(64 * 64) { 128.toByte() }
        val faint = ByteArray(64 * 64) { i -> (128 + if (((i / 64) / 4) % 2 == 0) 3 else -3).toByte() }
        assertTrue(
            PerceptualHash.distance(
                PerceptualHash.of(grey, 64, 64),
                PerceptualHash.of(faint, 64, 64),
            ) > 0,
        )
    }

    @Test
    fun `an image smaller than the working size still hashes`() {
        // A tiny thumbnail is a legitimate library item, and the box reduction has to cope
        // with upscaling as well as down.
        val tiny = photo(8, 8)
        val h = PerceptualHash.of(tiny, 8, 8)
        assertEquals(h, PerceptualHash.of(tiny, 8, 8))
    }

    @Test
    fun `the hash ignores aspect ratio, which is why the finder checks shape`() {
        // Everything is reduced to a square grid, so the same normalised picture at two
        // aspect ratios hashes alike. That is inherent to the construction, not a defect —
        // but it means the hash alone cannot decide that two files are the same photo,
        // and DuplicateFinder compares shape separately because of it.
        val wide = hash(1920, 480, seed = 3)
        val tall = hash(480, 1920, seed = 3)
        assertTrue(PerceptualHash.isNearDuplicate(wide, tall), "documenting the known blind spot")
    }

    @Test
    fun `distance is symmetric and zero on itself`() {
        val a = hash(640, 480, seed = 4)
        val b = hash(640, 480, seed = 5)
        assertEquals(0, PerceptualHash.distance(a, a))
        assertEquals(PerceptualHash.distance(a, b), PerceptualHash.distance(b, a))
    }

    @Test
    fun `the hash uses the whole 64 bits`() {
        // A construction that only ever set the low bits would collapse the distance scale
        // and quietly make the threshold mean something else.
        var seen = 0L
        (1..40).forEach { seed -> seen = seen or hash(320, 240, seed) }
        assertEquals(64, seen.countOneBits(), "only ${seen.countOneBits()} bits are ever set")
    }

    @Test
    fun `a malformed buffer is refused rather than read past its end`() {
        assertFailsWith<IllegalArgumentException> { PerceptualHash.of(ByteArray(10), 100, 100) }
        assertFailsWith<IllegalArgumentException> { PerceptualHash.of(ByteArray(10), 0, 10) }
    }
}
