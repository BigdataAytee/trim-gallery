package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.engine.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecLadderTest {

    private fun clip(bitrate: Long? = 40_000_000) = MediaItem(
        id = "m1",
        platformRef = MediaRef("content://x"),
        name = "clip.mp4",
        kind = MediaKind.VIDEO,
        codec = "avc1",
        width = 3840,
        height = 2160,
        fps = 30.0,
        bitrate = bitrate,
        size = 400L * 1024 * 1024,
        duration = 120_000,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    // ---------------------------------------------------------------- bracket

    @Test
    fun `the bracket is built from the source's own bitrate`() {
        val bounds = CodecLadder.fallbackBounds(clip(bitrate = 40_000_000), VideoCodec.HEVC)
        assertEquals(8_000_000, bounds.lowBps)
        assertEquals(34_000_000, bounds.highBps)
        assertEquals(24_800_000, bounds.startBps)
    }

    /**
     * The reason this class exists: AV1 reaches the same quality at roughly two thirds of
     * HEVC's bitrate, so a bracket built for HEVC opens an AV1 search a third too high and
     * spends every probe converging downwards.
     */
    @Test
    fun `AV1 searches lower than HEVC for the same file`() {
        val hevc = CodecLadder.fallbackBounds(clip(), VideoCodec.HEVC)
        val av1 = CodecLadder.fallbackBounds(clip(), VideoCodec.AV1)
        assertTrue(av1.startBps < hevc.startBps, "AV1 started at or above HEVC")
        assertEquals((hevc.startBps * CodecLadder.AV1_BITRATE_RATIO).toInt(), av1.startBps)
        assertTrue(av1.highBps < hevc.highBps)
        assertTrue(av1.lowBps < hevc.lowBps)
    }

    @Test
    fun `the starting point sits inside the bracket for both codecs`() {
        for (codec in VideoCodec.entries) {
            for (bitrate in listOf(2_000_000L, 12_000_000L, 40_000_000L, 200_000_000L)) {
                val bounds = CodecLadder.fallbackBounds(clip(bitrate), codec)
                assertTrue(bounds.startBps in bounds.lowBps..bounds.highBps, "$codec at $bitrate: $bounds")
                assertTrue(bounds.lowBps > 0, "$codec at $bitrate")
                assertTrue(bounds.highBps >= bounds.lowBps, "$codec at $bitrate")
            }
        }
    }

    /** A container that reports no bitrate must not produce a bracket of zero to zero. */
    @Test
    fun `a missing source bitrate still gives a usable bracket`() {
        for (codec in VideoCodec.entries) {
            for (bitrate in listOf(null, 0L, 1L)) {
                val bounds = CodecLadder.fallbackBounds(clip(bitrate), codec)
                assertTrue(bounds.lowBps >= CodecLadder.MIN_BPS, "$codec at $bitrate: $bounds")
                assertTrue(bounds.startBps in bounds.lowBps..bounds.highBps, "$codec at $bitrate: $bounds")
            }
        }
    }

    /**
     * The queue's estimate and the search's opening bid come from one number, so what the
     * user is told is possible and what the search goes looking for cannot drift apart.
     */
    @Test
    fun `the expected factor matches the starting point`() {
        val source = 40_000_000L
        for (codec in VideoCodec.entries) {
            val bounds = CodecLadder.fallbackBounds(clip(source), codec)
            assertEquals((source * CodecLadder.expectedFactor(codec)).toInt(), bounds.startBps, "$codec")
        }
        assertTrue(CodecLadder.expectedFactor(VideoCodec.AV1) < CodecLadder.expectedFactor(VideoCodec.HEVC))
    }

    // -------------------------------------------------------------- threshold

    /**
     * Both numbers are read off the milestone 2 sweep in `shared/native/calibration/`:
     * VMAF 95 interpolates to XPSNR y 39.8, and VMAF 90.035 was measured at 36.0.
     */
    @Test
    fun `the thresholds are the measured ones`() {
        assertEquals(39.8, CodecLadder.xpsnrThreshold(VideoCodec.HEVC, QualityTarget.STANDARD))
        assertEquals(36.0, CodecLadder.xpsnrThreshold(VideoCodec.HEVC, QualityTarget.COMPACT))
    }

    @Test
    fun `Compact asks less of the picture than Standard`() {
        for (codec in VideoCodec.entries) {
            assertTrue(
                CodecLadder.xpsnrThreshold(codec, QualityTarget.COMPACT) <
                    CodecLadder.xpsnrThreshold(codec, QualityTarget.STANDARD),
                "$codec",
            )
        }
    }

    /**
     * AV1 currently returns the HEVC calibration. That is a placeholder — recorded in
     * PROJECT.md and in the calibration README — and this test pins the shape of the table
     * rather than the belief that one calibration serves both codecs.
     */
    @Test
    fun `every codec and target has a threshold`() {
        for (codec in VideoCodec.entries) {
            for (target in QualityTarget.entries) {
                assertTrue(CodecLadder.xpsnrThreshold(codec, target) > 0, "$codec $target")
            }
        }
    }
}
