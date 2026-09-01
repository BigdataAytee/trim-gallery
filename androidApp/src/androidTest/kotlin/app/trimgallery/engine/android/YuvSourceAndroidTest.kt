package app.trimgallery.engine.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.YuvWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The decoder, run against a real clip on a real Android runtime.
 *
 * This one **can** be proved on the emulator, and that is worth saying because its sibling
 * cannot: BUILD.md § 2 rule 2 bans software *encoding*, so an ATD image has no hardware
 * encoder and the encode path skips itself there. Decoding has no such rule — a software
 * decoder is ordinary — so every assertion below is the real MediaCodec, the real extractor
 * and the real plane layouts of whatever device runs it.
 *
 * That matters more than usual here. `YuvSource` feeds the VMAF gate, and a decoder that
 * returns *plausible but wrong* buffers — sheared by row padding, or half-chroma from an
 * interleaved plane — would not crash. It would produce a number, and the number would be
 * used to decide whether someone's video may be replaced.
 */
@RunWith(AndroidJUnit4::class)
class YuvSourceAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var clip: File
    private lateinit var source: YuvSourceAndroid

    @Before
    fun prepare() {
        clip = File(context.cacheDir, GOLDEN).apply {
            outputStream().use { out ->
                InstrumentationRegistry.getInstrumentation().context.assets.open(GOLDEN).use { it.copyTo(out) }
            }
        }
        source = YuvSourceAndroid(context, MediaCodecFactory(context))
    }

    @Test
    fun decodesRealFramesAtTheRequestedWidth() = runBlocking {
        val window = source.decodeWindow(TempFile(clip.path), start = 0, len = 1_000, width = 320)

        assertTrue("no frames were decoded", window.frameCount > 0)
        assertEquals(320, window.width)
        // 640x360 scaled to 320 wide keeps its aspect: 180 tall.
        assertEquals(180, window.height)
    }

    @Test
    fun thePlanesAreExactlyTheSizeTheMetricsWillReadThemAs() = runBlocking {
        // The native metric functions take these buffers and a frame count. A plane one row
        // short, or one frame long, is read as picture anyway and scored as if it were.
        val window = source.decodeWindow(TempFile(clip.path), start = 0, len = 1_000, width = 320)

        val luma = window.width * window.height * window.frameCount
        val chroma = ((window.width + 1) / 2) * ((window.height + 1) / 2) * window.frameCount
        assertEquals(luma, window.y.size)
        assertEquals(chroma, window.u.size)
        assertEquals(chroma, window.v.size)
    }

    @Test
    fun theFramesHoldAPictureRatherThanAnEmptyBuffer() = runBlocking {
        // The failure this catches is the quiet one: a decode that returns correctly sized
        // buffers full of zeroes still scores, and scores identically every time.
        val window = source.decodeWindow(TempFile(clip.path), start = 0, len = 1_000, width = 320)

        assertTrue("the luma plane is entirely zero", window.y.any { it.toInt() != 0 })
        assertTrue("the picture has no variation in it", window.y.distinct().size > MIN_DISTINCT)
    }

    @Test
    fun aLaterWindowIsADifferentPictureFromTheFirst() = runBlocking {
        // Proves the seek actually moved. A source that ignored `start` would return frame
        // zero for all three of BUILD.md § 5's windows, and the file would be scored three
        // times on its opening second.
        val first = source.decodeWindow(TempFile(clip.path), start = 0, len = 500, width = 320)
        val later = source.decodeWindow(TempFile(clip.path), start = 2_000, len = 500, width = 320)

        assertTrue(first.frameCount > 0 && later.frameCount > 0)
        assertNotEquals(
            "the window at 2s decoded the same picture as the one at 0s",
            first.y.take(SAMPLE).toList(),
            later.y.take(SAMPLE).toList(),
        )
    }

    @Test
    fun aWidthLargerThanTheSourceIsNotUpscaled() = runBlocking {
        // Measuring a 640-wide clip at 1280 would score the scaler, not the encoder.
        val window = source.decodeWindow(TempFile(clip.path), start = 0, len = 500, width = 1_280)

        assertEquals(640, window.width)
        assertEquals(360, window.height)
    }

    @Test
    fun anUnreadableFileIsAnEmptyWindowRatherThanACrash() = runBlocking {
        val missing: YuvWindow = source.decodeWindow(TempFile("/does/not/exist.mp4"), 0, 1_000, 320)

        assertEquals(0, missing.frameCount)
        assertEquals(0, missing.y.size)
    }

    private companion object {
        const val GOLDEN = "golden-h264-640x360-3s.mp4"

        /** Enough distinct luma values that a flat or constant frame cannot pass. */
        const val MIN_DISTINCT = 8

        /** How much of a frame to compare between windows. A row is plenty. */
        const val SAMPLE = 320
    }
}
