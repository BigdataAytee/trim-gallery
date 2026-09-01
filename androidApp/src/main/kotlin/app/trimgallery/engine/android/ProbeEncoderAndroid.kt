package app.trimgallery.engine.android

import android.media.Image
import android.media.MediaCodec
import android.util.Log
import androidx.media3.common.util.UnstableApi
import app.trimgallery.engine.EncodeSpec
import app.trimgallery.engine.ProbeEncoder
import app.trimgallery.engine.Setting
import app.trimgallery.engine.VideoCodec
import app.trimgallery.engine.YuvWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * The other half of the search: encodes one cached window at one candidate setting, and
 * hands back what a player would see.
 *
 * `ProbeAndSearch` bisects bitrate by asking "how good does this file look at 4 Mbps?", and
 * the only way to answer that is to encode at 4 Mbps and measure. Nothing implemented this
 * on any platform, so milestone 3's search — written, unit tested against fakes, and
 * complete — could not run a single probe. Together with `YuvSourceAndroid` this is what
 * makes the search real.
 *
 * ## Encode, then decode
 *
 * XPSNR compares pictures, so a bitstream is not an answer. Each probe therefore runs a
 * hardware encoder and feeds its output straight into a decoder in the same loop, and the
 * decoded frames are the return value. No muxer, no container and no temp file: a probe has
 * its input already in memory and wants pixels back, and a search does twelve of these per
 * file. Writing and re-reading an MP4 each time would add a write, a read and a container
 * parse to every one of them.
 *
 * ## Hardware only, and what happens when there is none
 *
 * The encoder comes from `MediaCodecFactory.probeEncoder`, which offers hardware encoders
 * and nothing else (BUILD.md § 2 rule 2, and the codec guard that enforces it). When a
 * device has no hardware encoder for the codec, this returns an **empty window**. That is
 * the honest answer rather than a fallback: an empty window scores as unusable, the search
 * ends in `NotReachable`, and the file is skipped with a reason the user can read. It is
 * also exactly what happens on an emulator, which has no hardware encoder at all — see
 * `ProbeEncoderAndroidTest`, which asserts that outcome rather than pretending to prove an
 * encode that the image cannot perform.
 *
 * ## Priority
 *
 * Both codecs are created through the factory, so both carry `KEY_PRIORITY = 1`. The
 * codec-priority skill is explicit that this is not just for the encoder: a realtime-
 * priority decoder feeding a background encoder still holds a slot the foreground wants.
 */
@UnstableApi
class ProbeEncoderAndroid(private val codecs: MediaCodecFactory) : ProbeEncoder {

    override suspend fun encodeWindow(yuv: YuvWindow, setting: Setting, codec: VideoCodec, fps: Double): YuvWindow =
        withContext(Dispatchers.Default) {
            if (yuv.frameCount <= 0 || yuv.width <= 0 || yuv.height <= 0) return@withContext empty(yuv)

            val rate = if (fps > 0.0) fps else ASSUMED_FPS
            val spec = EncodeSpec(
                codec = codec,
                setting = setting,
                width = yuv.width,
                height = yuv.height,
                fps = rate,
            )

            // Null is "this device has no hardware encoder for this", and the empty window it
            // becomes is what stops the file rather than a software encode.
            val probe = codecs.probeEncoder(spec) ?: return@withContext empty(yuv)

            try {
                Pass(yuv, probe, codecs, rate).run()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
                // A codec that throws mid-probe is a probe that did not happen, not a night
                // that stops. The window scores as unusable and the file is skipped with a
                // reason, which is the same place a `NotReachable` search would have landed.
                //
                // Logged rather than swallowed. A device whose encoder refuses a shape we
                // thought it advertised is a real finding, and the only symptom otherwise is a
                // library where every video quietly lands in the Skipped list.
                Log.w(TAG, "probe encode failed at ${setting.bitrate} bps on $codec", failed)
                empty(yuv)
            } finally {
                runCatching { probe.codec.stop() }
                probe.codec.release()
            }
        }

    /**
     * One window through an encoder and back out of a decoder.
     *
     * A class rather than a function because it is a state machine over two codecs, and the
     * state is what makes it readable: which frame goes in next, whether the encoder has
     * been told there are no more, whether the decoder exists yet, and whether it has said
     * it is finished.
     *
     * The two are pumped in one loop rather than on two threads. Codec buffers are the only
     * thing either is waiting on, both are polled with a short timeout, and the decoder is
     * drained every pass — so the encoder can never be blocked by a decoder whose output
     * nobody is taking.
     */
    private class Pass(
        private val source: YuvWindow,
        private val encoder: MediaCodecFactory.Probe,
        private val codecs: MediaCodecFactory,
        private val fps: Double,
    ) {
        private val collected = YuvFrames(source.width, source.height)
        private val encoded = MediaCodec.BufferInfo()
        private val decoded = MediaCodec.BufferInfo()

        private var decoder: MediaCodec? = null
        private var nextFrame = 0
        private var encoderFed = false
        private var finished = false

        suspend fun run(): YuvWindow {
            try {
                while (!finished) {
                    // Cancellable between buffers rather than between files: the guards stop
                    // the night mid-search, and a probe that ignored that would hold both
                    // codecs open while the phone tried to give the hardware back.
                    coroutineContext.ensureActive()
                    feedEncoder()
                    drainEncoder()
                    drainDecoder()
                }
            } finally {
                decoder?.let {
                    runCatching { it.stop() }
                    it.release()
                }
            }
            return collected.window()
        }

        /** Copies the next source frame into an encoder input buffer, or signals the end. */
        private fun feedEncoder() {
            if (encoderFed) return
            val index = encoder.codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return

            val image = if (nextFrame < source.frameCount) encoder.codec.getInputImage(index) else null
            if (image == null) {
                encoder.codec.queueInputBuffer(index, 0, 0, timestampOf(nextFrame), END_OF_STREAM)
                encoderFed = true
            } else {
                writeFrame(image, nextFrame)
                // The size an encoder configured for flexible YUV expects for one frame. The
                // `Image` above describes the real layout, padding included; this is the
                // picture's own size, which is what the platform's own encoder tests pass.
                val size = encoder.width * encoder.height * 3 / 2
                encoder.codec.queueInputBuffer(index, 0, size, timestampOf(nextFrame), 0)
                nextFrame++
            }
        }

        /**
         * Copies one frame out of the flat window into the encoder's own buffers.
         *
         * Two things are happening at once. The destination is padded twice over — rows to
         * a stride, and the frame itself out to the encoder's alignment — and the source is
         * a tight plane inside a buffer holding every frame end to end. Reading either as
         * though it were the other produces a picture that is sheared or offset by a frame,
         * and an encode of the wrong picture still returns a perfectly good number.
         *
         * The padding replicates the edge rather than filling with black. A hard black
         * border costs real bits to encode, and those bits come out of the budget the probe
         * is meant to be measuring.
         */
        private fun writeFrame(image: Image, frame: Int) {
            val chromaWidth = (source.width + 1) / 2
            val chromaHeight = (source.height + 1) / 2
            val lumaSize = source.width * source.height
            val chromaSize = chromaWidth * chromaHeight

            Planes.write(
                dst = image.planes[0],
                src = source.y,
                srcOffset = frame * lumaSize,
                srcW = source.width,
                srcH = source.height,
                dstW = encoder.width,
                dstH = encoder.height,
            )
            Planes.write(
                dst = image.planes[1],
                src = source.u,
                srcOffset = frame * chromaSize,
                srcW = chromaWidth,
                srcH = chromaHeight,
                dstW = (encoder.width + 1) / 2,
                dstH = (encoder.height + 1) / 2,
            )
            Planes.write(
                dst = image.planes[2],
                src = source.v,
                srcOffset = frame * chromaSize,
                srcW = chromaWidth,
                srcH = chromaHeight,
                dstW = (encoder.width + 1) / 2,
                dstH = (encoder.height + 1) / 2,
            )
        }

        /** Takes what the encoder has produced and hands it to the decoder. */
        private suspend fun drainEncoder() {
            when (val index = encoder.codec.dequeueOutputBuffer(encoded, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                // The first thing an encoder emits, and the only place the decoder can be
                // built from: this format carries the codec-specific data (`csd-0`) that a
                // decoder needs before it can read a single frame.
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    decoder = codecs.decoder(encoder.codec.outputFormat)
                    if (decoder == null) finished = true
                }

                else -> if (index >= 0) {
                    val buffer = encoder.codec.getOutputBuffer(index)
                    // The codec-config buffer is already in the format the decoder was
                    // configured from; sending it again is a duplicate, not a frame.
                    val isConfig = encoded.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && encoded.size > 0 && !isConfig) feedDecoder(buffer)
                    val ended = encoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.codec.releaseOutputBuffer(index, false)
                    if (ended) endDecoderInput()
                }
            }
        }

        /**
         * Waits for a decoder input buffer, draining decoded frames while it waits.
         *
         * It has to wait rather than drop: an encoded buffer is a frame of the answer, and a
         * probe missing a frame in the middle is not a shorter probe, it is a different
         * picture. Draining inside the wait is what keeps that from deadlocking — the
         * decoder's input frees up only when its output is taken.
         */
        private suspend fun feedDecoder(data: ByteBuffer) {
            val target = decoder ?: return
            data.position(encoded.offset)
            data.limit(encoded.offset + encoded.size)

            while (true) {
                coroutineContext.ensureActive()
                val index = target.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    target.getInputBuffer(index)?.apply {
                        clear()
                        put(data)
                    }
                    target.queueInputBuffer(index, 0, encoded.size, encoded.presentationTimeUs, 0)
                    return
                }
                drainDecoder()
            }
        }

        private suspend fun endDecoderInput() {
            val target = decoder
            if (target == null) {
                finished = true
                return
            }
            while (true) {
                coroutineContext.ensureActive()
                val index = target.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    target.queueInputBuffer(index, 0, 0, 0, END_OF_STREAM)
                    return
                }
                drainDecoder()
            }
        }

        /** Collects decoded frames, cropped back to the size the window was asked for. */
        private fun drainDecoder() {
            val target = decoder ?: return
            val index = target.dequeueOutputBuffer(decoded, TIMEOUT_US)
            if (index < 0) return

            if (decoded.size > 0) {
                target.getOutputImage(index)?.let {
                    // The picture, without the padding that was added to satisfy the
                    // encoder's alignment. Measuring the padding would score the replicated
                    // edge as though it were part of the photograph.
                    collected.add(it, srcWidth = source.width, srcHeight = source.height)
                }
            }
            target.releaseOutputBuffer(index, false)
            if (decoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finished = true
        }

        private fun timestampOf(frame: Int): Long = (frame * MICROS_PER_SECOND / fps).toLong()
    }

    /** Writing one plane into a codec buffer, which is the part that is easy to get wrong. */
    private object Planes {
        @Suppress("LongParameterList")
        fun write(dst: Image.Plane, src: ByteArray, srcOffset: Int, srcW: Int, srcH: Int, dstW: Int, dstH: Int) {
            val buffer = dst.buffer
            val rowStride = dst.rowStride
            val pixelStride = dst.pixelStride
            val base = buffer.position()

            for (y in 0 until dstH) {
                // Rows past the source repeat its last one; same for columns, below.
                val sourceRow = srcOffset + minOf(y, srcH - 1) * srcW
                val rowStart = base + y * rowStride
                for (x in 0 until dstW) {
                    buffer.put(rowStart + x * pixelStride, src[sourceRow + minOf(x, srcW - 1)])
                }
            }
        }
    }

    private fun empty(like: YuvWindow) = YuvWindow(
        width = like.width.coerceAtLeast(1),
        height = like.height.coerceAtLeast(1),
        frameCount = 0,
        y = ByteArray(0),
        u = ByteArray(0),
        v = ByteArray(0),
    )

    private companion object {
        const val TAG = "TrimProbeEncoder"
        const val ASSUMED_FPS = 30.0
        const val MICROS_PER_SECOND = 1_000_000.0
        const val END_OF_STREAM = MediaCodec.BUFFER_FLAG_END_OF_STREAM

        /**
         * How long to wait on a codec buffer.
         *
         * Ten milliseconds, matching `YuvSourceAndroid`: long enough not to be a spin, short
         * enough that cancellation is noticed. There is deliberately no overall deadline —
         * the guards cancel a night that has run out of time, and inventing a timeout here
         * would abandon slow-but-working hardware mid-probe.
         */
        const val TIMEOUT_US = 10_000L
    }
}
