package app.trimgallery.core.pipeline.verify

import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.engine.Image
import app.trimgallery.engine.Ms
import app.trimgallery.engine.OutputProbe
import app.trimgallery.engine.ProbedOutput
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.YuvSource
import app.trimgallery.engine.YuvWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * BUILD.md rule 3: *"Never delete or replace an original until the replacement has been
 * verified."* These are the tests that decide what "verified" means, so they are written
 * against the failure modes rather than the happy path.
 */
class VerifierTest {

    private val original = MediaRef("original")
    private val encoded = TempFile("/data/app/tmp/out.mp4")

    private class FakeProbe(var result: ProbedOutput?) : OutputProbe {
        override suspend fun probe(file: TempFile): ProbedOutput? = result
    }

    private class FakeYuv : YuvSource {
        val refWindows = mutableListOf<Pair<Ms, Ms>>()
        val distWindows = mutableListOf<Pair<Ms, Ms>>()
        var requestedWidth = 0

        override suspend fun decodeWindow(ref: MediaRef, start: Ms, len: Ms, width: Int): YuvWindow {
            refWindows += start to len
            requestedWidth = width
            return window(width)
        }

        override suspend fun decodeWindow(file: TempFile, start: Ms, len: Ms, width: Int): YuvWindow {
            distWindows += start to len
            return window(width)
        }

        private fun window(width: Int) =
            YuvWindow(width, 1080, 1, ByteArray(1), ByteArray(1), ByteArray(1))
    }

    /** Returns the scores in order, so a test can make one window bad. */
    private class FakeScorer(private val scores: List<Double>) : QualityScorer {
        var subsample = 0
        private var index = 0
        override suspend fun xpsnr(a: YuvWindow, b: YuvWindow): Double = 0.0
        override suspend fun vmaf(a: YuvWindow, b: YuvWindow, subsample: Int): Double {
            this.subsample = subsample
            return scores[index++ % scores.size]
        }
        override suspend fun ssim2(a: Image, b: Image): Double = 0.0
    }

    private fun request(
        originalSize: Long = 100_000_000,
        durationMs: Long = 60_000,
        hasAudio: Boolean = true,
        target: QualityTarget = QualityTarget.STANDARD,
        careful: Boolean = false,
    ) = Verifier.Request(
        original = original,
        originalSize = originalSize,
        originalDurationMs = durationMs,
        originalWidth = 3840,
        originalHeight = 2160,
        originalHasAudio = hasAudio,
        encoded = encoded,
        target = target,
        careful = careful,
    )

    private fun probed(
        durationMs: Long = 60_000,
        hasVideo: Boolean = true,
        hasAudio: Boolean = true,
        size: Long = 40_000_000,
    ) = ProbedOutput(durationMs, hasVideo, hasAudio, size)

    private fun verifier(
        probe: ProbedOutput? = probed(),
        scores: List<Double> = listOf(97.0),
        yuv: FakeYuv = FakeYuv(),
        scorer: FakeScorer = FakeScorer(scores),
    ) = Verifier(FakeProbe(probe), yuv, scorer)

    @Test
    fun `a good encode passes`() = runTest {
        val outcome = verifier().verify(request())
        val passed = assertIs<Verifier.Outcome.Passed>(outcome)
        assertEquals(97.0, passed.vmaf)
        assertEquals(40_000_000, passed.newSize)
    }

    @Test
    fun `a file that will not open is never replaced`() = runTest {
        val outcome = verifier(probe = null).verify(request())
        assertIs<Verifier.Outcome.Unplayable>(outcome)
    }

    @Test
    fun `a truncated output is rejected`() = runTest {
        // The classic muxer failure: the encode "succeeds" and writes 48 of 60 seconds.
        val outcome = verifier(probe = probed(durationMs = 48_000)).verify(request())
        val bad = assertIs<Verifier.Outcome.Unplayable>(outcome)
        assertTrue(bad.detail.contains("48000"), bad.detail)
    }

    @Test
    fun `container timestamp drift within tolerance is accepted`() = runTest {
        // A faithful remux routinely lands a frame or two out; that is not truncation.
        val outcome = verifier(probe = probed(durationMs = 60_040)).verify(request())
        assertIs<Verifier.Outcome.Passed>(outcome)
    }

    @Test
    fun `losing the audio track is a failure, not a saving`() = runTest {
        // Audio is passed through, never re-encoded. A smaller file with no sound would
        // otherwise sail through the size gate.
        val outcome = verifier(probe = probed(hasAudio = false)).verify(request(hasAudio = true))
        val bad = assertIs<Verifier.Outcome.Unplayable>(outcome)
        assertTrue(bad.detail.contains("audio"), bad.detail)
    }

    @Test
    fun `a silent original is not expected to gain audio`() = runTest {
        val outcome = verifier(probe = probed(hasAudio = false)).verify(request(hasAudio = false))
        assertIs<Verifier.Outcome.Passed>(outcome)
    }

    @Test
    fun `an empty output is rejected before anything is scored`() = runTest {
        val yuv = FakeYuv()
        val outcome = verifier(probe = probed(size = 0), yuv = yuv).verify(request())
        assertIs<Verifier.Outcome.Unplayable>(outcome)
        assertTrue(yuv.refWindows.isEmpty(), "an unopenable file must not be decoded")
    }

    @Test
    fun `the worst window decides, not the mean`() = runTest {
        // 99, 99, 88 averages to 95.3 and would pass on a mean. The whole reason for
        // sampling three windows is to catch the one place the encode fell apart.
        val outcome = verifier(scores = listOf(99.0, 99.0, 88.0)).verify(request())
        val below = assertIs<Verifier.Outcome.BelowTarget>(outcome)
        assertEquals(88.0, below.vmaf)
        assertEquals(95, below.target)
    }

    @Test
    fun `exactly at the target passes`() = runTest {
        assertIs<Verifier.Outcome.Passed>(verifier(scores = listOf(95.0)).verify(request()))
    }

    @Test
    fun `Compact mode gates at 90`() = runTest {
        val outcome = verifier(scores = listOf(92.0)).verify(request(target = QualityTarget.COMPACT))
        assertIs<Verifier.Outcome.Passed>(outcome)
        assertIs<Verifier.Outcome.BelowTarget>(
            verifier(scores = listOf(89.0)).verify(request(target = QualityTarget.COMPACT)),
        )
    }

    @Test
    fun `an output that is not smaller is skipped, never replaced`() = runTest {
        val outcome = verifier(probe = probed(size = 120_000_000)).verify(request(originalSize = 100_000_000))
        val bigger = assertIs<Verifier.Outcome.NotSmaller>(outcome)
        assertEquals(120_000_000, bigger.newSize)
    }

    @Test
    fun `an output of exactly the same size is not an improvement`() = runTest {
        val outcome = verifier(probe = probed(size = 100_000_000)).verify(request(originalSize = 100_000_000))
        assertIs<Verifier.Outcome.NotSmaller>(outcome)
    }

    @Test
    fun `three windows are scored at start, middle and end`() = runTest {
        val yuv = FakeYuv()
        verifier(yuv = yuv, scores = listOf(97.0)).verify(request(durationMs = 60_000))
        assertEquals(listOf(0L to 5000L, 27500L to 5000L, 55000L to 5000L), yuv.refWindows)
        assertEquals(yuv.refWindows, yuv.distWindows, "both files must be sampled identically")
    }

    @Test
    fun `Careful mode verifies every window instead of three`() = runTest {
        val yuv = FakeYuv()
        verifier(probe = probed(durationMs = 32_000), yuv = yuv, scores = listOf(97.0))
            .verify(request(durationMs = 32_000, careful = true))
        assertEquals(7, yuv.refWindows.size)
        assertEquals(0L, yuv.refWindows.first().first)
        // No gaps: the windows tile the file exactly.
        assertEquals(32_000L, yuv.refWindows.sumOf { it.second })
    }

    @Test
    fun `scoring happens at 1080p and n_subsample 10`() = runTest {
        val yuv = FakeYuv()
        val scorer = FakeScorer(listOf(97.0))
        Verifier(FakeProbe(probed()), yuv, scorer).verify(request())
        // 3840x2160 scaled to 1080 high is 1920 wide.
        assertEquals(1920, yuv.requestedWidth)
        assertEquals(10, scorer.subsample)
    }
}
