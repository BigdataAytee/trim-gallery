package app.trimgallery.engine.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trimgallery.engine.Setting
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.VideoCodec
import app.trimgallery.engine.YuvWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The probe encoder, and an honest account of what a CI emulator can prove about it.
 *
 * **An ATD emulator image has no hardware video encoder.** BUILD.md § 2 rule 2 forbids the
 * software one, so the encode itself cannot run here and no test below claims it does. What
 * *can* be proved on this image is the thing most worth proving:
 *
 * > When there is no hardware encoder, this returns an empty window and does not encode.
 *
 * That is the rule the whole app rests on, and a CI machine that lacks the hardware is
 * exactly the machine where a fallback would be tempting and would go unnoticed. So the
 * first test asserts the outcome *matches what the device says it can do* — empty here,
 * real frames on a phone — and the rest of the suite runs its assertions on whichever side
 * of that the runner lands on.
 *
 * The encode path proper is proved on a real device, and its result is reported as such
 * rather than inferred from a green tick here.
 */
@RunWith(AndroidJUnit4::class)
class ProbeEncoderAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var codecs: MediaCodecFactory
    private lateinit var probe: ProbeEncoderAndroid
    private lateinit var window: YuvWindow

    @Before
    fun prepare() = runBlocking {
        codecs = MediaCodecFactory(context)
        probe = ProbeEncoderAndroid(codecs)

        // A real decoded window rather than a synthetic buffer: the plane strides, the
        // frame count and the chroma layout are all things this class has to get right, and
        // a hand-built window would agree with whatever this class assumed.
        val clip = File(context.cacheDir, GOLDEN).apply {
            outputStream().use { out ->
                InstrumentationRegistry.getInstrumentation().context.assets.open(GOLDEN).use { it.copyTo(out) }
            }
        }
        window = YuvSourceAndroid(context, codecs)
            .decodeWindow(TempFile(clip.path), start = 0, len = 1_000, width = 320)
        assertTrue("the fixture window decoded no frames", window.frameCount > 0)
    }

    @Test
    fun theOutcomeMatchesWhatTheDeviceSaysItCanEncode() = runBlocking {
        // The hardware-only rule, asserted from both sides. On a machine with no hardware
        // encoder the only permitted answer is an empty window — never a software encode,
        // never a crash, and never a window of frames that came from somewhere else.
        val hardware = codecs.capabilities().forCodec(VideoCodec.HEVC).hardware

        val encoded = probe.encodeWindow(window, Setting(bitrate = 2_000_000), VideoCodec.HEVC, fps = 30.0)

        if (hardware) {
            assertTrue("a device with a hardware encoder produced no frames", encoded.frameCount > 0)
        } else {
            assertEquals("a device with no hardware encoder produced frames anyway", 0, encoded.frameCount)
            assertEquals(0, encoded.y.size)
        }
    }

    @Test
    fun anEmptyWindowIsAnEmptyWindowRatherThanACrash() = runBlocking {
        // The window `YuvSource` returns for an unreadable file. It reaches here because the
        // search does not inspect what it decoded; nothing may throw on it.
        val nothing = YuvWindow(320, 180, frameCount = 0, y = ByteArray(0), u = ByteArray(0), v = ByteArray(0))

        val encoded = probe.encodeWindow(nothing, Setting(bitrate = 2_000_000), VideoCodec.HEVC, fps = 30.0)

        assertEquals(0, encoded.frameCount)
    }

    @Test
    fun theEncodedWindowIsTheSameSizeAsTheSource() = runBlocking {
        assumeTrue(NO_HARDWARE_ENCODER, codecs.capabilities().forCodec(VideoCodec.HEVC).hardware)

        // The metrics compare the two buffer for buffer. A window that came back padded out
        // to the encoder's alignment would be compared against the source off by a column.
        val encoded = probe.encodeWindow(window, Setting(bitrate = 2_000_000), VideoCodec.HEVC, fps = 30.0)

        assertEquals(window.width, encoded.width)
        assertEquals(window.height, encoded.height)
        assertEquals(encoded.width * encoded.height * encoded.frameCount, encoded.y.size)
        val chroma = ((encoded.width + 1) / 2) * ((encoded.height + 1) / 2) * encoded.frameCount
        assertEquals(chroma, encoded.u.size)
        assertEquals(chroma, encoded.v.size)
    }

    @Test
    fun theResultIsThePictureAgainRatherThanTheSourceHandedBack() = runBlocking {
        assumeTrue(NO_HARDWARE_ENCODER, codecs.capabilities().forCodec(VideoCodec.HEVC).hardware)

        // Two failures at once. An implementation that returned its input would score a
        // perfect XPSNR at every bitrate and the search would pick the lowest one on the
        // ladder; one that returned zeroes would score the same terrible number at every
        // bitrate and every file would be skipped. The real answer is neither: recognisably
        // the same picture, not bit-identical to it.
        val encoded = probe.encodeWindow(window, Setting(bitrate = 400_000), VideoCodec.HEVC, fps = 30.0)

        assertTrue(encoded.frameCount > 0)
        assertTrue("the encoded window is entirely zero", encoded.y.any { it.toInt() != 0 })
        assertNotEquals(
            "the probe returned its own input",
            window.y.take(SAMPLE).toList(),
            encoded.y.take(SAMPLE).toList(),
        )
    }

    @Test
    fun moreBitrateIsCloserToTheSource() = runBlocking {
        assumeTrue(NO_HARDWARE_ENCODER, codecs.capabilities().forCodec(VideoCodec.HEVC).hardware)

        // The property the whole search depends on. `SettingSearch` bisects on the
        // assumption that quality rises with bitrate; if this class did not honour the
        // setting it was handed, the search would still terminate and still return a
        // number, and that number would be noise.
        val cheap = probe.encodeWindow(window, Setting(bitrate = 200_000), VideoCodec.HEVC, fps = 30.0)
        val rich = probe.encodeWindow(window, Setting(bitrate = 8_000_000), VideoCodec.HEVC, fps = 30.0)

        assertTrue(cheap.frameCount > 0 && rich.frameCount > 0)
        assertTrue(
            "8 Mbps was no closer to the source than 200 kbps",
            meanError(window, rich) < meanError(window, cheap),
        )
    }

    /** Mean absolute luma difference over the frames both windows have. */
    private fun meanError(a: YuvWindow, b: YuvWindow): Double {
        val length = minOf(a.y.size, b.y.size)
        if (length == 0) return Double.MAX_VALUE
        var total = 0L
        for (i in 0 until length) {
            total += kotlin.math.abs((a.y[i].toInt() and BYTE) - (b.y[i].toInt() and BYTE))
        }
        return total.toDouble() / length
    }

    private companion object {
        const val GOLDEN = "golden-h264-640x360-3s.mp4"
        const val SAMPLE = 320
        const val BYTE = 0xFF

        /**
         * Why the encode assertions do not run on an ATD emulator.
         *
         * Printed by the runner as the skip reason, so a green CI run says which of these
         * tests actually executed rather than implying all of them did.
         */
        const val NO_HARDWARE_ENCODER =
            "this device has no hardware HEVC encoder, and BUILD.md rule 2 forbids the software one"
    }
}
