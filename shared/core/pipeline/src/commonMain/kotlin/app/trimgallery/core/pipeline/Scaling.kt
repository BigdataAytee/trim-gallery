package app.trimgallery.core.pipeline

/**
 * How big a window is decoded before it is scored.
 *
 * Both metrics are resolution-sensitive in ways that matter: XPSNR's block weighting
 * adapts to frame size, and libvmaf's default model is trained on 1080p viewing. Scoring
 * at a fixed height therefore keeps thresholds comparable across a mixed library, and —
 * since the search re-encodes the window once per probe — keeps the cost of the search
 * independent of the size of the file being searched.
 */
internal object Scaling {

    /**
     * Width that puts a `srcWidth`×`srcHeight` frame at [targetHeight], preserving aspect.
     *
     * Never upscales: a 720p source scored at 1080p would be measuring the scaler, not
     * the encoder. Rounded down to an even number, because chroma planes are half-width
     * in 4:2:0 and an odd width leaves the last luma column without one.
     */
    fun widthFor(srcWidth: Int, srcHeight: Int, targetHeight: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return even(targetHeight)
        if (srcHeight <= targetHeight) return even(srcWidth)
        return even(srcWidth * targetHeight / srcHeight)
    }

    /** Height actually used, given that [widthFor] never upscales. */
    fun heightFor(srcHeight: Int, targetHeight: Int): Int =
        if (srcHeight <= 0) even(targetHeight) else even(minOf(srcHeight, targetHeight))

    private fun even(v: Int): Int = (v and 1.inv()).coerceAtLeast(2)
}
