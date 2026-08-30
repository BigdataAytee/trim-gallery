package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.engine.Image
import app.trimgallery.engine.Ms
import app.trimgallery.engine.ProbeEncoder
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.Setting
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.VideoCodec
import app.trimgallery.engine.YuvSource
import app.trimgallery.engine.YuvWindow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ARCHITECTURE.md § 2.7: every platform class ships with a fake, and the pipeline is
 * tested against those rather than a device.
 */
class ProbeAndSearchTest {

    private class FakeYuvSource(private val width: Int = 1280, private val height: Int = 720) : YuvSource {
        val decoded = mutableListOf<Pair<Ms, Ms>>()

        override suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int): YuvWindow {
            decoded += start to len
            return YuvWindow(width, height, frameCount = 1, y = ByteArray(1), u = ByteArray(1), v = ByteArray(1))
        }

        override suspend fun decodeWindow(file: TempFile, start: Ms, len: Ms, width: Int): YuvWindow =
            YuvWindow(width, height, frameCount = 1, y = ByteArray(1), u = ByteArray(1), v = ByteArray(1))
    }

    /** Quality rises with bitrate, as the real metric does (asserted in milestone 2). */
    private class FakeScorer(private val cutoffBps: Int) : QualityScorer {
        val scored = mutableListOf<Int>()
        var lastSetting: Setting? = null

        override suspend fun xpsnr(a: YuvWindow, b: YuvWindow): Double {
            val bitrate = lastSetting?.bitrate ?: cutoffBps
            scored += bitrate
            return 40.0 + 8.0 * kotlin.math.log2(bitrate.toDouble() / cutoffBps)
        }

        override suspend fun vmaf(a: YuvWindow, b: YuvWindow, subsample: Int): Double = 96.0
        override suspend fun ssim2(a: Image, b: Image): Double = 90.0
    }

    /** Wires the encoder's setting through to the scorer, as the real pipeline does. */
    private class RecordingEncoder(private val scorer: FakeScorer) : ProbeEncoder {
        val encodes = mutableListOf<Setting>()

        override suspend fun encodeWindow(yuv: YuvWindow, setting: Setting): YuvWindow {
            encodes += setting
            scorer.lastSetting = setting
            return yuv
        }
    }

    private fun item(durationMs: Long = 60_000, width: Int = 1920, height: Int = 1080) = MediaItem(
        id = "1", platformRef = MediaRef("ref"), name = "clip.mp4", kind = MediaKind.VIDEO,
        codec = "avc1", width = width, height = height, fps = 30.0, bitrate = 12_000_000,
        size = 100_000_000, duration = durationMs, takenAt = null, location = null,
        cameraModel = "Pixel 9 rear", phash = null, sha256 = null, mtime = 0,
    )

    private val fallback = SettingSearch.Bounds(1_000_000, 20_000_000, 10_000_000)

    @Test
    fun `the source window is decoded once and reused by every probe`() = runTest {
        // PROJECT.md section Speed: this is the difference between a search that costs
        // one decode and one that costs four.
        val source = FakeYuvSource()
        val scorer = FakeScorer(cutoffBps = 3_300_000)
        val encoder = RecordingEncoder(scorer)

        val result = ProbeAndSearch(source, encoder, scorer).run(
            item = item(),
            threshold = 40.0,
            fallback = fallback,
            prediction = null,
        )

        assertEquals(1, source.decoded.size, "decoded ${source.decoded.size} times")
        assertTrue(encoder.encodes.size > 1, "should have probed more than once")
        assertIs<SettingSearch.Outcome.Found>(result.outcome)
    }

    @Test
    fun `a long file decodes three windows, still once each`() = runTest {
        val source = FakeYuvSource()
        val scorer = FakeScorer(3_300_000)
        val encoder = RecordingEncoder(scorer)

        val result = ProbeAndSearch(source, encoder, scorer).run(
            item = item(durationMs = 600_000),
            threshold = 40.0,
            fallback = fallback,
            prediction = null,
        )

        assertEquals(3, source.decoded.size)
        assertEquals(3, result.windowsDecoded)
        // Every probe encodes all three windows.
        assertEquals(result.probes * 3, encoder.encodes.size)
    }

    @Test
    fun `windows are decoded at 720p, not at source resolution`() = runTest {
        // Scoring at source resolution would make the search cost scale with exactly the
        // files that most need optimising.
        var requestedWidth = 0
        val source = object : YuvSource {
            override suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int): YuvWindow {
                requestedWidth = width
                return YuvWindow(width, 720, 1, ByteArray(1), ByteArray(1), ByteArray(1))
            }

            override suspend fun decodeWindow(file: TempFile, start: Ms, len: Ms, width: Int): YuvWindow =
                YuvWindow(width, 720, 1, ByteArray(1), ByteArray(1), ByteArray(1))
        }
        val scorer = FakeScorer(3_300_000)
        ProbeAndSearch(source, RecordingEncoder(scorer), scorer).run(
            item = item(width = 3840, height = 2160),
            threshold = 40.0,
            fallback = fallback,
            prediction = null,
        )
        // 3840x2160 scaled to 720 high is 1280 wide.
        assertEquals(1280, requestedWidth)
    }

    @Test
    fun `a file already below the scoring height is not upscaled`() = runTest {
        var requestedWidth = 0
        val source = object : YuvSource {
            override suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int): YuvWindow {
                requestedWidth = width
                return YuvWindow(width, 480, 1, ByteArray(1), ByteArray(1), ByteArray(1))
            }

            override suspend fun decodeWindow(file: TempFile, start: Ms, len: Ms, width: Int): YuvWindow =
                YuvWindow(width, 480, 1, ByteArray(1), ByteArray(1), ByteArray(1))
        }
        val scorer = FakeScorer(3_300_000)
        ProbeAndSearch(source, RecordingEncoder(scorer), scorer).run(
            item = item(width = 854, height = 480),
            threshold = 40.0,
            fallback = fallback,
            prediction = null,
        )
        assertEquals(854, requestedWidth)
    }

    @Test
    fun `the scoring width is always even, for 4-2-0 chroma`() = runTest {
        // An odd width leaves the last column without a chroma sample.
        listOf(1919 to 1079, 1281 to 721, 999 to 555).forEach { (w, h) ->
            var requestedWidth = 0
            val source = object : YuvSource {
                override suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int): YuvWindow {
                    requestedWidth = width
                    return YuvWindow(width, 720, 1, ByteArray(1), ByteArray(1), ByteArray(1))
                }

                override suspend fun decodeWindow(file: TempFile, start: Ms, len: Ms, width: Int): YuvWindow =
                    YuvWindow(width, 720, 1, ByteArray(1), ByteArray(1), ByteArray(1))
            }
            val scorer = FakeScorer(3_300_000)
            ProbeAndSearch(source, RecordingEncoder(scorer), scorer).run(
                item = item(width = w, height = h),
                threshold = 40.0,
                fallback = fallback,
                prediction = null,
            )
            assertEquals(0, requestedWidth % 2, "width $requestedWidth from ${w}x$h is odd")
        }
    }

    @Test
    fun `a confident prediction cuts the number of probes`() = runTest {
        // The end-to-end payoff of the predictor table.
        val scorer = FakeScorer(4_800_000)
        val withoutPrediction = ProbeAndSearch(FakeYuvSource(), RecordingEncoder(scorer), scorer)
            .run(item(), 40.0, fallback, prediction = null).probes

        val scorer2 = FakeScorer(4_800_000)
        val entry = Predictor.Entry(
            Predictor.keyOf(item(), "android", "Pixel 9", VideoCodec.HEVC),
            settingBps = 5_000_000,
            samples = Predictor.CONFIDENT_SAMPLES,
        )
        val withPrediction = ProbeAndSearch(FakeYuvSource(), RecordingEncoder(scorer2), scorer2)
            .run(item(), 40.0, fallback, prediction = entry).probes

        assertTrue(
            withPrediction < withoutPrediction,
            "prediction should cost fewer probes: $withPrediction vs $withoutPrediction",
        )
        assertTrue(withPrediction <= 2, "BUILD.md expects 1-2 probes with a prediction, got $withPrediction")
    }

    @Test
    fun `a file with no duration is reported rather than probed`() = runTest {
        val source = FakeYuvSource()
        val scorer = FakeScorer(3_300_000)
        val result = ProbeAndSearch(source, RecordingEncoder(scorer), scorer).run(
            item = item().copy(duration = null),
            threshold = 40.0,
            fallback = fallback,
            prediction = null,
        )
        assertEquals(0, source.decoded.size)
        assertIs<SettingSearch.Outcome.NotReachable>(result.outcome)
    }

    @Test
    fun `an unreachable file reports the probes it spent`() = runTest {
        val scorer = FakeScorer(cutoffBps = 400_000_000)
        val result = ProbeAndSearch(FakeYuvSource(), RecordingEncoder(scorer), scorer).run(
            item(),
            threshold = 40.0,
            fallback = fallback,
            prediction = null,
        )
        assertIs<SettingSearch.Outcome.NotReachable>(result.outcome)
        assertTrue(result.probes > 0)
    }
}
