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
        if (dstW <= 0 || dstH <= 0 || srcW <= 0 || srcH <= 0) return

        for (dy in 0 until dstH) {
            val y0 = dy * srcH / dstH
            val y1 = (((dy + 1) * srcH + dstH - 1) / dstH).coerceAtMost(srcH).coerceAtLeast(y0 + 1)

            for (dx in 0 until dstW) {
                val x0 = dx * srcW / dstW
                val x1 = (((dx + 1) * srcW + dstW - 1) / dstW).coerceAtMost(srcW).coerceAtLeast(x0 + 1)

                var total = 0
                var count = 0
                for (sy in y0 until y1) {
                    val row = sy * rowStride
                    for (sx in x0 until x1) {
                        val index = row + sx * pixelStride
                        // A short final row is a truncated buffer, not a reason to crash:
                        // the frames this runs on come from a decoder on someone's phone.
                        if (index < src.size) {
                            total += src[index].toInt() and BYTE_MASK
                            count++
                        }
                    }
                }
                dst[dstOffset + dy * dstW + dx] = if (count == 0) 0 else (total / count).toByte()
            }
        }
    }

    private const val BYTE_MASK = 0xFF
}
