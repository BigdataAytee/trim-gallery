package app.trimgallery.engine.android

import app.trimgallery.engine.Image
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.YuvWindow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Android implementation of [QualityScorer], over the native C ABI.
 *
 * Two jobs the raw binding deliberately does not do: turn a negative return code into
 * something the pipeline can act on, and bridge coroutine cancellation onto the flag the
 * native side polls. Without the second, a night pass told to stop — unplugged, thermal,
 * past the user's alarm — would keep scoring until the current window finished, which on
 * a 5-second 1080p window is not a short wait.
 */
internal class NativeQualityScorer : QualityScorer {

    /**
     * Direct buffers, reused across calls.
     *
     * [YuvWindow] carries `ByteArray` because `shared/engine-api` cannot name
     * `java.nio.ByteBuffer`, so each plane has to be copied into a direct buffer the
     * native side can address. Reusing them makes that one copy rather than a copy plus
     * a multi-megabyte allocation per probe, and the search runs many probes per file.
     * Milestone 3 should decode straight into these; see PROJECT.md.
     */
    private var scratch: Array<ByteBuffer?> = arrayOfNulls(PLANES_PER_WINDOW * 2)

    override suspend fun xpsnr(a: YuvWindow, b: YuvWindow): Double = withContext(Dispatchers.Default) {
        withCancelFlag { cancel ->
            val out = DoubleArray(1)
            val code = TrimNative.nativeXpsnr(
                direct(0, a.y), direct(1, a.u), direct(2, a.v), a.width, a.chromaWidth, a.chromaWidth,
                direct(3, b.y), direct(4, b.u), direct(5, b.v), b.width, b.chromaWidth, b.chromaWidth,
                a.width, a.height, a.frameCount, DEFAULT_FRAME_RATE,
                cancel, out,
            )
            resultOf(code, out)
        }
    }

    override suspend fun vmaf(a: YuvWindow, b: YuvWindow, subsample: Int): Double =
        withContext(Dispatchers.Default) {
            withCancelFlag { cancel ->
                val out = DoubleArray(1)
                val code = TrimNative.nativeVmaf(
                    direct(0, a.y), direct(1, a.u), direct(2, a.v), a.width, a.chromaWidth, a.chromaWidth,
                    direct(3, b.y), direct(4, b.u), direct(5, b.v), b.width, b.chromaWidth, b.chromaWidth,
                    a.width, a.height, a.frameCount, subsample,
                    cancel, out,
                )
                resultOf(code, out)
            }
        }

    /**
     * SSIMULACRA 2, the photo gate (BUILD.md § 5).
     *
     * Not on the reusable scratch buffers the video path uses: a still is scored a handful
     * of times during one bisection and then never again, while a video window is scored
     * once per probe against the same reference. Holding a megapixel pair alive between
     * photos would cost more than the copy saves.
     */
    override suspend fun ssim2(a: Image, b: Image): Double = withContext(Dispatchers.Default) {
        require(a.width == b.width && a.height == b.height) {
            "SSIMULACRA2 needs two images of the same size"
        }
        withCancelFlag { cancel ->
            val out = DoubleArray(1)
            val code = TrimNative.nativeSsim2(
                copyOf(a.rgba), a.width * RGBA_BYTES,
                copyOf(b.rgba), b.width * RGBA_BYTES,
                a.width, a.height,
                cancel, out,
            )
            resultOf(code, out)
        }
    }

    private fun copyOf(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }

    /**
     * Runs [block] with a flag the native loop polls between frames, raised when this
     * coroutine is cancelled.
     *
     * A watchdog coroutine rather than a callback from C: calling back into the JVM from
     * inside the metric's own loop would pay JNI on every frame, which the `ndk-build`
     * skill rules out for exactly this reason.
     */
    private suspend fun <T> withCancelFlag(block: (ByteBuffer) -> T): T = coroutineScope {
        val flag = ByteBuffer.allocateDirect(Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val watchdog = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                // Runs whether the scope was cancelled or the metric simply finished;
                // raising it after the fact is harmless.
                flag.putInt(0, 1)
            }
        }
        try {
            block(flag)
        } finally {
            watchdog.cancel()
        }
    }

    /** Copies one plane into its reusable direct buffer. */
    private fun direct(slot: Int, plane: ByteArray): ByteBuffer {
        val existing = scratch[slot]
        val buffer = if (existing != null && existing.capacity() >= plane.size) {
            existing
        } else {
            ByteBuffer.allocateDirect(plane.size).order(ByteOrder.nativeOrder()).also { scratch[slot] = it }
        }
        buffer.clear()
        buffer.put(plane)
        buffer.rewind()
        return buffer
    }

    private fun resultOf(code: Int, out: DoubleArray): Double = when (code) {
        TrimNative.OK -> out[0]
        // Cancellation is the expected outcome of a guard firing, not a failure.
        TrimNative.ERR_CANCELLED -> throw CancellationException("metric cancelled")
        TrimNative.ERR_INVALID_ARG -> error("invalid window passed to the native metric")
        TrimNative.ERR_UNSUPPORTED -> error("native metric unavailable in this build")
        else -> error("native metric failed (code $code)")
    }

    private val YuvWindow.chromaWidth: Int get() = (width + 1) / 2

    private companion object {
        /** Packed RGBA, as `trim_image` carries it. */
        const val RGBA_BYTES = 4

        const val PLANES_PER_WINDOW = 3

        /** Phone capture is 30 or 60 fps; XPSNR switches its temporal term above 32. */
        const val DEFAULT_FRAME_RATE = 30
    }
}
