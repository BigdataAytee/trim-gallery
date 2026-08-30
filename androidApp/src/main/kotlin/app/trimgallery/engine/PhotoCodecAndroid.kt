package app.trimgallery.engine.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.heifwriter.HeifWriter
import app.trimgallery.engine.Image
import app.trimgallery.engine.PhotoCodec
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android still-image codecs (BUILD.md § 5, milestone 7).
 *
 * Three of the four paths are native and identical on both platforms — jpegli, JPEG XL and
 * oxipng all live behind `trim_native.h`, so the numbers this app produces do not depend on
 * which phone it is running on. Only HEIC is the platform's, because BUILD.md § 2.2 bans
 * software video encoding and HEIC is an HEVC still: `HeifWriter` drives the same hardware
 * encoder the video path uses, which is both faster and the only way to stay inside that
 * rule.
 *
 * Every buffer handed to the native side is a direct `ByteBuffer`. A megapixel still is
 * megabytes, and the search encodes it several times.
 */
class PhotoCodecAndroid(private val cacheDir: File) : PhotoCodec {

    override suspend fun decode(src: ByteArray): Image? = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // No downscaling. The gate compares the encode against the original at full
            // size; scoring a thumbnail would answer a question nobody asked.
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(src, 0, src.size, options)
            ?: return@withContext null

        try {
            val pixels = ByteBuffer.allocate(bitmap.width * bitmap.height * BYTES_PER_PIXEL)
            bitmap.copyPixelsToBuffer(pixels)
            Image(bitmap.width, bitmap.height, pixels.array())
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun jpegli(src: ByteArray, q: Int): ByteArray = withContext(Dispatchers.IO) {
        transcode(src) { input, length, out, outLen -> TrimNative.nativeJpegli(input, length, q, out, outLen) }
    }

    override suspend fun jxlRecompress(src: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        transcode(src, TrimNative::nativeJxlRecompress)
    }

    override suspend fun pngOptimise(src: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        transcode(src, TrimNative::nativePngOptimise)
    }

    /**
     * HEIC through the platform writer.
     *
     * `HeifWriter` writes to a file rather than to memory, so this goes via app-private
     * scratch and reads the bytes straight back. That is not a detour worth avoiding: the
     * alternative is a `MediaCodec` and a muxer assembled by hand, which would put codec
     * creation outside `MediaCodecFactory` and fail the build guard (ARCHITECTURE.md § 14)
     * — correctly, because it is exactly the kind of second codec path BUILD.md rule 2
     * exists to prevent.
     */
    override suspend fun heic(src: Image, q: Int): ByteArray = withContext(Dispatchers.IO) {
        val scratch = File(cacheDir, "heic-${System.nanoTime()}.heic")
        try {
            val writer = HeifWriter.Builder(scratch.absolutePath, src.width, src.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(q)
                .setMaxImages(1)
                .build()

            writer.use {
                it.start()
                it.addBitmap(src.toBitmap())
                it.stop(HEIF_TIMEOUT_MS)
            }
            scratch.readBytes()
        } finally {
            scratch.delete()
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        return bitmap
    }

    /**
     * The ABI's two-call convention, once, so the three codecs do not each re-implement it.
     *
     * Ask for the size, allocate exactly that, fill it. Guessing a capacity and growing on
     * failure would encode twice for nothing.
     */
    private inline fun transcode(
        src: ByteArray,
        call: (ByteBuffer, Int, ByteBuffer?, IntArray) -> Int,
    ): ByteArray {
        val input = ByteBuffer.allocateDirect(src.size)
        input.put(src)
        input.rewind()

        val length = IntArray(1)
        check(call(input, src.size, null, length) == TrimNative.OK) { "native sizing failed" }
        check(length[0] > 0) { "native codec reported a zero-length result" }

        val output = ByteBuffer.allocateDirect(length[0])
        val rc = call(input, src.size, output, length)
        check(rc == TrimNative.OK) { "native codec failed with $rc" }

        val bytes = ByteArray(length[0])
        output.rewind()
        output.get(bytes)
        return bytes
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4

        /**
         * How long to wait for the hardware encoder to finish one still.
         *
         * Generous, because the night pass runs beside a video encode that may be holding
         * the same silicon. A timeout here fails one photo; the guards decide whether the
         * night carries on.
         */
        const val HEIF_TIMEOUT_MS = 30_000L
    }
}
