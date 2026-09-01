package app.trimgallery.engine.android

import android.media.Image
import app.trimgallery.core.pipeline.YuvScale
import app.trimgallery.engine.YuvWindow

/**
 * Collects decoded frames into the three flat planes the metric functions read.
 *
 * Shared by the two things that decode: `YuvSourceAndroid`, which scales a source window
 * down to the scoring height, and `ProbeEncoderAndroid`, which crops a padded probe frame
 * back to the size it was asked to encode. Both are the same operation seen through
 * [YuvScale.plane] — a box average over a source region — and the region is the only
 * difference, which is why it is a parameter rather than two copies of this class.
 *
 * The plane arithmetic is the part worth having in one place. A `MediaCodec` output image
 * is not a tight buffer: rows are padded to a stride, chroma may be interleaved with a
 * pixel stride of two, and reading it as though it were tight produces buffers that are the
 * right size, full of plausible values, and sheared. Nothing downstream would notice —
 * XPSNR would simply return a number, and that number decides whether someone's video is
 * replaced.
 */
internal class YuvFrames(private val width: Int, private val height: Int) {
    private val luma = ArrayList<ByteArray>()
    private val blue = ArrayList<ByteArray>()
    private val red = ArrayList<ByteArray>()

    val count: Int get() = luma.size

    private val chromaWidth get() = (width + 1) / 2
    private val chromaHeight get() = (height + 1) / 2

    /**
     * Takes one frame and closes it.
     *
     * @param srcWidth the region of [image] to read, from its top-left corner. The whole
     *   image by default; less than that when the image is padded out to an encoder's
     *   alignment and the padding is not part of the picture.
     */
    fun add(image: Image, srcWidth: Int = image.width, srcHeight: Int = image.height) {
        try {
            luma += scaled(image.planes[0], srcWidth, srcHeight, width, height)
            val chromaSrcWidth = (srcWidth + 1) / 2
            val chromaSrcHeight = (srcHeight + 1) / 2
            blue += scaled(image.planes[1], chromaSrcWidth, chromaSrcHeight, chromaWidth, chromaHeight)
            red += scaled(image.planes[2], chromaSrcWidth, chromaSrcHeight, chromaWidth, chromaHeight)
        } finally {
            image.close()
        }
    }

    @Suppress("LongParameterList")
    private fun scaled(plane: Image.Plane, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val out = ByteArray(dstW * dstH)
        YuvScale.plane(
            src = bytes,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            srcW = srcW,
            srcH = srcH,
            dst = out,
            dstOffset = 0,
            dstW = dstW,
            dstH = dstH,
        )
        return out
    }

    fun window() = YuvWindow(
        width = width,
        height = height,
        frameCount = luma.size,
        y = luma.flatten(),
        u = blue.flatten(),
        v = red.flatten(),
    )

    /** Frames end to end, which is the layout the native metric functions read. */
    private fun List<ByteArray>.flatten(): ByteArray {
        val out = ByteArray(sumOf { it.size })
        var offset = 0
        forEach { frame ->
            frame.copyInto(out, offset)
            offset += frame.size
        }
        return out
    }
}
