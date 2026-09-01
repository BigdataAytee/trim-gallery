package app.trimgallery.engine.android

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.pipeline.YuvScale
import app.trimgallery.engine.Ms
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.YuvSource
import app.trimgallery.engine.YuvWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Decodes a few seconds of video into planar YUV, for the two things that measure quality.
 *
 * This port had **no implementation on any platform**. `ProbeAndSearch` needs it to score
 * candidate settings and `Verifier` needs it to compare an encode against its original —
 * so milestone 3's search and milestone 4's VMAF gate were both written, unit tested
 * against fakes, and unable to run. The gate that stands between a video and a worse copy
 * of it had nothing to decode with.
 *
 * ## Why it decodes windows rather than files
 *
 * BUILD.md § 5 scores three five-second windows, not the whole clip, and the windows are
 * decoded once and reused by every probe (PROJECT.md § Speed). A ten-minute 4K clip decoded
 * per probe would be four full decodes to answer a question that fifteen seconds answers.
 *
 * ## What it does not do
 *
 * It does not encode, does not write, and cannot name a place in the user's library to put
 * anything. The `TempFile` overload exists precisely so that comparing an *output* never
 * requires handing shared code a `MediaRef` it could pass to something that writes.
 */
class YuvSourceAndroid(private val context: Context, private val codecs: MediaCodecFactory) : YuvSource {

    override suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int): YuvWindow =
        decode(start, len, width) { setDataSource(context, Uri.parse(ref.value), null) }

    override suspend fun decodeWindow(file: TempFile, start: Ms, len: Ms, width: Int): YuvWindow =
        decode(start, len, width) { setDataSource(file.path) }

    private suspend fun decode(start: Ms, len: Ms, width: Int, open: MediaExtractor.() -> Unit): YuvWindow =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            try {
                // A source that cannot be opened is an empty window, not an exception. Triage
                // has already decided this file is worth trying; a window of no frames scores
                // as unusable and the file is skipped, which is the same answer arrived at
                // without taking the night down.
                runCatching { extractor.open() }.getOrElse { return@withContext empty(width) }

                val track = videoTrackOf(extractor) ?: return@withContext empty(width)
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                val sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (sourceWidth <= 0 || sourceHeight <= 0) return@withContext empty(width)

                // Never upscale: a 720p source measured at 1080p would be scoring the scaler.
                val outWidth = even(if (width in 1 until sourceWidth) width else sourceWidth)
                val outHeight = even(sourceHeight * outWidth / sourceWidth)

                extractor.seekTo(start * US_PER_MS, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val codec = codecs.decoder(format) ?: return@withContext empty(width)

                try {
                    drain(extractor, codec, start, len, outWidth, outHeight)
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        }

    /**
     * Feeds the decoder and collects the frames whose timestamps fall inside the window.
     *
     * Seeking lands on the previous sync frame, which is usually *before* the window: the
     * decoder needs those frames to reconstruct the ones asked for, but they are not part of
     * the answer. So every frame is decoded and only the ones inside the window are kept.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun drain(
        extractor: MediaExtractor,
        codec: MediaCodec,
        start: Ms,
        len: Ms,
        outWidth: Int,
        outHeight: Int,
    ): YuvWindow {
        val startUs = start * US_PER_MS
        val endUs = (start + len) * US_PER_MS
        val frames = Frames(outWidth, outHeight)
        val info = MediaCodec.BufferInfo()
        var fed = false
        var drained = false

        while (!drained) {
            // Cancellable between buffers rather than only between files: the guards stop
            // the night mid-window, and a decode that ignored that would hold the codec.
            coroutineContext.ensureActive()

            if (!fed) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fed = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (index >= 0) {
                    val presentedUs = info.presentationTimeUs
                    if (info.size > 0 && presentedUs >= startUs && presentedUs < endUs) {
                        codec.getOutputImage(index)?.let { frames.add(it) }
                    }
                    codec.releaseOutputBuffer(index, false)

                    val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    // Past the window is done: decoding the rest of a ten-minute clip to
                    // reach an end-of-stream flag would cost the whole file to measure five
                    // seconds of it.
                    if (ended || presentedUs >= endUs) drained = true
                }
            }
        }
        return frames.window()
    }

    /** Collects scaled frames, one plane at a time, into the buffers the metrics take. */
    private class Frames(private val width: Int, private val height: Int) {
        private val luma = ArrayList<ByteArray>()
        private val blue = ArrayList<ByteArray>()
        private val red = ArrayList<ByteArray>()

        private val chromaWidth get() = (width + 1) / 2
        private val chromaHeight get() = (height + 1) / 2

        fun add(image: Image) {
            try {
                luma += scaled(image.planes[0], image.width, image.height, width, height)
                blue += scaled(image.planes[1], image.width / 2, image.height / 2, chromaWidth, chromaHeight)
                red += scaled(image.planes[2], image.width / 2, image.height / 2, chromaWidth, chromaHeight)
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

    private fun videoTrackOf(extractor: MediaExtractor): Int? = (0 until extractor.trackCount)
        .firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }

    private fun empty(width: Int) = YuvWindow(
        width = width.coerceAtLeast(1),
        height = 1,
        frameCount = 0,
        y = ByteArray(0),
        u = ByteArray(0),
        v = ByteArray(0),
    )

    /** Chroma planes are half-width in 4:2:0; an odd luma width leaves a column without one. */
    private fun even(value: Int): Int = (value and 1.inv()).coerceAtLeast(2)

    private companion object {
        const val US_PER_MS = 1_000L

        /**
         * How long to wait on a codec buffer.
         *
         * Ten milliseconds: long enough that the loop is not a spin, short enough that
         * cancellation is noticed promptly. The loop has no overall timeout on purpose —
         * a decode that stalls is cancelled by the guards, and inventing a deadline here
         * would abandon slow-but-fine hardware mid-window.
         */
        const val TIMEOUT_US = 10_000L
    }
}
