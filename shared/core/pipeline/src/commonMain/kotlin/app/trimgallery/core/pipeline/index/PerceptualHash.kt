package app.trimgallery.core.pipeline.index

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A 64-bit perceptual hash, for finding near-duplicates (BUILD.md § 7, § 8).
 *
 * *"Perceptual hash + exact hash → duplicates and near-duplicates (bursts, repeated
 * screenshots, edited copies)."*
 *
 * **Entirely shared, on purpose.** ARCHITECTURE.md § 6 lists the perceptual hash as a
 * "shared Kotlin impl" on both platforms, and it has to be: a hash computed by Android's
 * scaler and one computed by Core Image would disagree, and the duplicate groups a user
 * built on one phone would dissolve the moment their library moved to another. The
 * platform hands over grayscale pixels; everything from there is arithmetic done here.
 *
 * The construction is the standard DCT one, and its properties are what the duplicate
 * finder relies on: it survives re-compression, resizing and small brightness changes,
 * because those barely move the low-frequency coefficients; and it does **not** survive
 * rotation or cropping, which is correct — a cropped photo is a different photo, and
 * BUILD.md asks for bursts and repeated screenshots, not for image search.
 */
object PerceptualHash {

    /**
     * The working size the image is reduced to before the transform.
     *
     * Thirty-two, so that the 8×8 block of low-frequency coefficients taken from it is a
     * genuine low-pass of the picture rather than most of it. Reducing straight to 8×8 and
     * thresholding — the "average hash" — is far cheaper and far worse: it responds to
     * overall brightness rather than to structure, and puts every dim photograph in the
     * same bucket.
     */
    const val WORKING_SIZE = 32

    /** Bits in the hash, one per low-frequency coefficient. */
    const val HASH_BITS = 64

    /**
     * Hamming distance at or below which two images are treated as the same picture.
     *
     * Ten of sixty-four. Below about six the hash misses genuine burst frames, where the
     * subject has moved slightly between shots; above about twelve it starts joining
     * different photographs taken in the same place, which is the failure the user
     * notices — being offered a stranger's photo to delete destroys trust in the whole
     * screen. Duplicate review is a suggestion the user confirms (BUILD.md § 8), so the
     * threshold errs toward showing a pair rather than hiding one, but not far.
     */
    const val NEAR_DUPLICATE_DISTANCE = 10

    /** The cosine basis, computed once: 32 × 32 doubles, reused for every image. */
    private val basis: DoubleArray = DoubleArray(WORKING_SIZE * WORKING_SIZE) { i ->
        val x = i / WORKING_SIZE
        val u = i % WORKING_SIZE
        cos((2.0 * x + 1.0) * u * kotlin.math.PI / (2.0 * WORKING_SIZE))
    }

    private val scale: DoubleArray = DoubleArray(WORKING_SIZE) { u ->
        if (u == 0) sqrt(1.0 / WORKING_SIZE) else sqrt(2.0 / WORKING_SIZE)
    }

    /**
     * Hashes an 8-bit grayscale image of any size.
     *
     * @param gray one byte per pixel, row-major, `width * height` long.
     *
     * The reduction to [WORKING_SIZE] is a box average rather than a nearest-neighbour
     * pick, because nearest-neighbour on a screenshot lands on whichever pixel happens to
     * be under the sample point and makes the hash depend on the source resolution — which
     * is precisely what has to be invariant for an "edited copy" to match its original.
     */
    fun of(gray: ByteArray, width: Int, height: Int): Long {
        require(width > 0 && height > 0) { "an image needs positive dimensions" }
        require(gray.size >= width * height) { "grayscale buffer is ${gray.size} for ${width}x$height" }

        val reduced = boxReduce(gray, width, height)
        val coefficients = dct(reduced)

        // The 64 lowest-frequency coefficients after the DC term, in zig-zag order.
        //
        // The DC term is excluded rather than thresholded: it is the image's mean
        // brightness, it is an order of magnitude larger than everything around it, and a
        // bit comparing it against the median of its neighbours is set for every image
        // ever hashed. That is a wasted bit — sixty-three working bits where the type says
        // sixty-four — and it took a test asserting that every bit is used to notice.
        val low = DoubleArray(HASH_BITS) { i -> coefficients[ZIGZAG[i + 1]] }

        val median = median(low)

        var hash = 0L
        for (i in low.indices) {
            if (low[i] > median) hash = hash or (1L shl i)
        }
        return hash
    }

    /** Bits that differ. 0 is identical; 32 is the expected value for unrelated images. */
    fun distance(a: Long, b: Long): Int = (a xor b).countOneBits()

    /** Whether two hashes are close enough to be the same picture. */
    fun isNearDuplicate(a: Long, b: Long, threshold: Int = NEAR_DUPLICATE_DISTANCE): Boolean =
        distance(a, b) <= threshold

    /** Averages each source block down to one working pixel. */
    private fun boxReduce(gray: ByteArray, width: Int, height: Int): DoubleArray {
        val out = DoubleArray(WORKING_SIZE * WORKING_SIZE)
        for (ty in 0 until WORKING_SIZE) {
            val y0 = ty * height / WORKING_SIZE
            val y1 = maxOf(y0 + 1, (ty + 1) * height / WORKING_SIZE)
            for (tx in 0 until WORKING_SIZE) {
                val x0 = tx * width / WORKING_SIZE
                val x1 = maxOf(x0 + 1, (tx + 1) * width / WORKING_SIZE)

                var total = 0.0
                var count = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        total += (gray[row + x].toInt() and 0xFF).toDouble()
                        count += 1
                    }
                }
                out[ty * WORKING_SIZE + tx] = total / count
            }
        }
        return out
    }

    /**
     * 2-D DCT-II, done as rows then columns.
     *
     * Separable, so this is 2 × 32 × 32 × 32 multiply-adds rather than 32⁴ — about 65,000
     * operations per image. That matters: this runs over every new file in a library that
     * may hold a hundred thousand of them.
     */
    private fun dct(input: DoubleArray): DoubleArray {
        val rows = DoubleArray(WORKING_SIZE * WORKING_SIZE)
        for (y in 0 until WORKING_SIZE) {
            for (u in 0 until WORKING_SIZE) {
                var sum = 0.0
                for (x in 0 until WORKING_SIZE) {
                    sum += input[y * WORKING_SIZE + x] * basis[x * WORKING_SIZE + u]
                }
                rows[y * WORKING_SIZE + u] = sum * scale[u]
            }
        }

        val out = DoubleArray(WORKING_SIZE * WORKING_SIZE)
        for (u in 0 until WORKING_SIZE) {
            for (v in 0 until WORKING_SIZE) {
                var sum = 0.0
                for (y in 0 until WORKING_SIZE) {
                    sum += rows[y * WORKING_SIZE + u] * basis[y * WORKING_SIZE + v]
                }
                out[v * WORKING_SIZE + u] = sum * scale[v]
            }
        }
        return out
    }

    /**
     * The median of the retained coefficients.
     *
     * The median rather than the mean: one very bright or very dark region skews a mean and
     * flips bits across the whole hash, which is exactly the instability that makes a
     * perceptual hash useless on photographs with a sky in them.
     */
    private fun median(values: DoubleArray): Double {
        val sorted = values.copyOf()
        sorted.sort()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

    /**
     * Indices into the 32×32 transform, ordered by increasing frequency.
     *
     * The zig-zag JPEG uses, for the same reason JPEG uses it: it visits coefficients in
     * roughly the order the eye cares about them, so the first 65 entries are the 65
     * lowest-frequency terms rather than a square block that mixes a horizontal detail
     * coefficient with a much coarser vertical one.
     */
    private val ZIGZAG: IntArray = buildZigZag()

    private fun buildZigZag(): IntArray {
        val order = ArrayList<Int>(HASH_BITS + 1)
        var u = 0
        var v = 0
        var goingUp = true
        while (order.size <= HASH_BITS) {
            order += v * WORKING_SIZE + u
            if (goingUp) {
                if (u ==
                    WORKING_SIZE - 1
                ) {
                    v++
                    goingUp = false
                } else if (v ==
                    0
                ) {
                    u++
                    goingUp = false
                } else {
                    u++
                    v--
                }
            } else {
                if (v ==
                    WORKING_SIZE - 1
                ) {
                    u++
                    goingUp = true
                } else if (u == 0) {
                    v++
                    goingUp = true
                } else {
                    u--
                    v++
                }
            }
        }
        return order.toIntArray()
    }
}
