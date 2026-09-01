package app.trimgallery.core.pipeline

/**
 * Downscales one YUV plane into a tightly packed destination.
 *
 * The metrics work at a fixed height ([Scaling] picks it) so that a 4K clip and a 1080p one
 * are scored on comparable pictures and a 4K probe does not cost four times a 1080p one.
 * Something has to do the resampling, and a decoder will not: it hands back frames at the
 * size they were encoded, in whatever plane layout the device prefers.
 *
 * ## Box average, not nearest
 *
 * Every destination pixel is the mean of the source pixels it covers. Nearest-neighbour
 * would be a third of the arithmetic and quite wrong for this use: dropping rows and columns
 * aliases high-frequency detail into low-frequency noise, and the whole purpose of the
 * number being computed downstream is to notice when detail has been lost. A scaler that
 * invented its own artefacts would be measured as if the encoder had made them.
 *
 * ## It reads a decoder's layout, not an array of pixels
 *
 * [rowStride] and [pixelStride] are what `android.media.Image.Plane` reports, and they are
 * rarely 1: chroma planes commonly arrive interleaved (`pixelStride` 2, the U and V of NV12
 * sharing one buffer), and rows are padded to hardware alignment. Reading such a plane as if
 * it were packed produces a picture that is sheared, doubled, or both — and it would still
 * *score*, which is the dangerous part.
 */
object YuvScale {

    /**
     * Writes a [dstW]×[dstH] packed plane into [dst] at [dstOffset].
     *
     * Never upscales usefully: with a destination larger than the source each box collapses
     * to one pixel and the result is nearest-neighbour. `Scaling.widthFor` never asks for
     * that, and this stays defined rather than throwing so a mangled source cannot take the
     * night down.
     */
    @Suppress("LongParameterList")
    fun plane(
        src: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        srcW: Int,
        srcH: Int,
        dst: ByteArray,
        dstOffset: Int,
        dstW: Int,
        dstH: Int,
    ) {
        if (srcW <= 0 || srcH <= 0) return
        if (dstW <= 0 || dstH <= 0) return

        for (dy in 0 until dstH) {
            val top = dy * srcH / dstH
            val bottom = boxEnd(dy, srcH, dstH, top)

            for (dx in 0 until dstW) {
                val left = dx * srcW / dstW
                val right = boxEnd(dx, srcW, dstW, left)
                dst[dstOffset + dy * dstW + dx] = mean(src, rowStride, pixelStride, left, right, top, bottom)
            }
        }
    }

    /**
     * The exclusive end of the source box a destination pixel covers, never empty.
     *
     * Rounded up so that no source row or column falls between two destination pixels and is
     * simply never read — over a whole plane that is a subtle sharpening, which the metric
     * downstream would attribute to the encoder.
     */
    private fun boxEnd(index: Int, srcSize: Int, dstSize: Int, start: Int): Int =
        (((index + 1) * srcSize + dstSize - 1) / dstSize).coerceAtMost(srcSize).coerceAtLeast(start + 1)

    /**
     * The mean of one box, read through the decoder's own strides.
     *
     * Its own function because the alternative is four nested loops in [plane], and because
     * "the average of the source pixels this destination pixel covers" is the whole idea —
     * worth a name rather than worth reconstructing from indices.
     */
    @Suppress("LongParameterList")
    private fun mean(
        src: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): Byte {
        var total = 0
        var count = 0
        for (sy in top until bottom) {
            val row = sy * rowStride
            for (sx in left until right) {
                val index = row + sx * pixelStride
                // A short final row is a truncated buffer, not a reason to crash: these
                // frames come from a decoder on someone's phone.
                if (index < src.size) {
                    total += src[index].toInt() and BYTE_MASK
                    count++
                }
            }
        }
        return if (count == 0) 0 else (total / count).toByte()
    }

    private const val BYTE_MASK = 0xFF
}
