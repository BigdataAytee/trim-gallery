package app.trimgallery.core.pipeline

import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.engine.CodecCaps
import app.trimgallery.engine.EncoderCaps
import app.trimgallery.engine.PerformancePoint
import app.trimgallery.engine.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodecChoiceTest {

    private fun clip(codec: String = "avc1", width: Int = 3840, height: Int = 2160, fps: Double = 30.0) =
        MediaItem(
            id = "m1",
            platformRef = MediaRef("content://x"),
            name = "clip.mp4",
            kind = MediaKind.VIDEO,
            codec = codec,
            width = width,
            height = height,
            fps = fps,
            bitrate = 40_000_000,
            size = 400L * 1024 * 1024,
            duration = 120_000,
            takenAt = null,
            location = null,
            cameraModel = null,
            phash = null,
            sha256 = null,
            mtime = 0,
        )

    private val hevcOnly = CodecCaps(
        hevc = EncoderCaps(hardware = true, maxWidth = 3840, maxHeight = 2160, maxFps = 60.0, cqSupported = true),
    )

    private val both = hevcOnly.copy(
        av1 = EncoderCaps(hardware = true, maxWidth = 3840, maxHeight = 2160, maxFps = 60.0),
    )

    private val proWithAv1 = Settings(allowAv1 = true)

    private fun encode(choice: CodecChoice.Choice) = assertIs<CodecChoice.Choice.Encode>(choice)

    // ------------------------------------------------------------- the default

    /** BUILD.md § 10: HEVC on all devices. AV1 is the exception, not the rule. */
    @Test
    fun `HEVC is the default everywhere`() {
        val choice = encode(CodecChoice.choose(clip(), both, Settings(), Tier.PRO))
        assertEquals(VideoCodec.HEVC, choice.codec)
        assertEquals(CodecChoice.Reason.HEVC_DEFAULT, choice.reason)
    }

    @Test
    fun `AV1 needs the setting and the entitlement together`() {
        assertFalse(CodecChoice.av1Permitted(Settings(allowAv1 = false), Tier.PRO))
        assertFalse(CodecChoice.av1Permitted(Settings(allowAv1 = true), Tier.FREE))
        assertTrue(CodecChoice.av1Permitted(Settings(allowAv1 = true), Tier.PRO))
    }

    /**
     * `SettingsPolicy.sanitise` already clears `allowAv1` for a free tier, and this checks
     * it again rather than trusting it: a codec choice that silently depended on something
     * else having sanitised first is one refactor from encoding a free user's library into
     * a format they did not choose.
     */
    @Test
    fun `a free user does not get AV1 even with the flag set`() {
        val choice = encode(CodecChoice.choose(clip(), both, proWithAv1, Tier.FREE))
        assertEquals(VideoCodec.HEVC, choice.codec)
    }

    @Test
    fun `a Pro user who turned it on gets AV1`() {
        val choice = encode(CodecChoice.choose(clip(), both, proWithAv1, Tier.PRO))
        assertEquals(VideoCodec.AV1, choice.codec)
        assertEquals(CodecChoice.Reason.AV1_ALLOWED, choice.reason)
    }

    @Test
    fun `AV1 is not chosen on a device without an AV1 encoder`() {
        val choice = encode(CodecChoice.choose(clip(), hevcOnly, proWithAv1, Tier.PRO))
        assertEquals(VideoCodec.HEVC, choice.codec)
        assertEquals(CodecChoice.Reason.AV1_CANNOT_SUSTAIN, choice.reason)
    }

    // ------------------------------------------------------- the AV1 source rule

    /**
     * Taking an AV1 clip to HEVC would usually make it *larger* for the same picture — a
     * night's battery spent to lose the user space.
     */
    @Test
    fun `an AV1 source is only ever re-encoded to AV1`() {
        val choice = encode(CodecChoice.choose(clip(codec = "av01"), both, proWithAv1, Tier.PRO))
        assertEquals(VideoCodec.AV1, choice.codec)
        assertEquals(CodecChoice.Reason.SOURCE_IS_AV1, choice.reason)
    }

    @Test
    fun `an AV1 source on an HEVC-only device is skipped, not downgraded`() {
        val choice = CodecChoice.choose(clip(codec = "av01"), hevcOnly, proWithAv1, Tier.PRO)
        assertEquals(SkipReason.NO_HARDWARE_ENCODER, assertIs<CodecChoice.Choice.Skip>(choice).reason)
    }

    /** The same rule holds for a free user, who cannot turn AV1 on at all. */
    @Test
    fun `an AV1 source is skipped rather than taken to HEVC for a free user`() {
        val choice = CodecChoice.choose(clip(codec = "av01"), both, Settings(), Tier.FREE)
        assertEquals(SkipReason.NO_HARDWARE_ENCODER, assertIs<CodecChoice.Choice.Skip>(choice).reason)
    }

    @Test
    fun `both spellings of the codec are recognised`() {
        for (name in listOf("av01", "av1", "AV01.0.08M.08", "video/av01")) {
            val choice = encode(CodecChoice.choose(clip(codec = name), both, proWithAv1, Tier.PRO))
            assertEquals(CodecChoice.Reason.SOURCE_IS_AV1, choice.reason, name)
        }
    }

    // --------------------------------------------------------------- throughput

    /**
     * The defect the per-codec split fixed, from the choosing side: most phones with an AV1
     * encoder top out below their HEVC ceiling.
     */
    @Test
    fun `a frame the AV1 encoder cannot hold goes to HEVC`() {
        val caps = hevcOnly.copy(
            av1 = EncoderCaps(hardware = true, maxWidth = 3840, maxHeight = 2160, maxFps = 30.0),
        )
        val choice = encode(CodecChoice.choose(clip(fps = 60.0), caps, proWithAv1, Tier.PRO))
        assertEquals(VideoCodec.HEVC, choice.codec)
        assertEquals(CodecChoice.Reason.AV1_CANNOT_SUSTAIN, choice.reason)
    }

    /** BUILD.md § 10: never request beyond advertised throughput. */
    @Test
    fun `the advertised performance points bind the choice`() {
        val caps = hevcOnly.copy(
            av1 = EncoderCaps(
                hardware = true,
                maxWidth = 3840,
                maxHeight = 2160,
                maxFps = 60.0,
                performancePoints = listOf(PerformancePoint(1920, 1080, 60)),
            ),
        )
        assertEquals(
            VideoCodec.HEVC,
            encode(CodecChoice.choose(clip(), caps, proWithAv1, Tier.PRO)).codec,
        )
        assertEquals(
            VideoCodec.AV1,
            encode(CodecChoice.choose(clip(width = 1920, height = 1080), caps, proWithAv1, Tier.PRO)).codec,
        )
    }

    /** A portrait clip is the same number of macroblocks turned on its side. */
    @Test
    fun `a performance point covers the same frame either way up`() {
        val point = PerformancePoint(1920, 1080, 60)
        assertTrue(point.covers(1920, 1080, 60.0))
        assertTrue(point.covers(1080, 1920, 60.0))
        assertFalse(point.covers(3840, 2160, 30.0))
    }

    /** Containers report 29.97 as 30 often enough that an exact comparison would refuse it. */
    @Test
    fun `a frame rate a hair over is not a refusal`() {
        assertTrue(PerformancePoint(1920, 1080, 30).covers(1920, 1080, 29.97))
        assertFalse(PerformancePoint(1920, 1080, 30).covers(1920, 1080, 60.0))
    }

    // ---------------------------------------------------------------- the speed

    /**
     * BUILD.md § 6 caps the night in minutes, so an encoder at half real time turns a night
     * that would have cleared four hours of video into one that clears one. The saving per
     * file is larger and the saving per night is smaller.
     */
    @Test
    fun `a measurably slow AV1 encoder loses to HEVC`() {
        val slow = CodecChoice.MeasuredSpeed(realtimeMultiple = 0.6, samples = 20)
        val choice = encode(CodecChoice.choose(clip(), both, proWithAv1, Tier.PRO, av1Speed = slow))
        assertEquals(VideoCodec.HEVC, choice.codec)
        assertEquals(CodecChoice.Reason.AV1_TOO_SLOW, choice.reason)
    }

    @Test
    fun `an AV1 encoder that keeps up keeps the work`() {
        val fine = CodecChoice.MeasuredSpeed(realtimeMultiple = 2.4, samples = 20)
        assertEquals(
            VideoCodec.AV1,
            encode(CodecChoice.choose(clip(), both, proWithAv1, Tier.PRO, av1Speed = fine)).codec,
        )
    }

    /**
     * One slow file is a file, not a fact about the encoder. Demoting AV1 for the life of
     * the phone on the strength of one thermally-throttled clip would be invisible.
     */
    @Test
    fun `one slow file is not enough to demote the encoder`() {
        val once = CodecChoice.MeasuredSpeed(realtimeMultiple = 0.3, samples = 1)
        assertEquals(
            VideoCodec.AV1,
            encode(CodecChoice.choose(clip(), both, proWithAv1, Tier.PRO, av1Speed = once)).codec,
        )
        assertFalse(once.confident)
        assertTrue(CodecChoice.MeasuredSpeed(0.3, CodecChoice.CONFIDENT_SAMPLES).confident)
    }

    /** An encoder that has never been measured cannot be demoted; the first files measure it. */
    @Test
    fun `an unmeasured AV1 encoder is given the work`() {
        assertEquals(
            VideoCodec.AV1,
            encode(CodecChoice.choose(clip(), both, proWithAv1, Tier.PRO, av1Speed = null)).codec,
        )
    }

    /** Speed never overrides the AV1-source rule: a bigger file is worse than a slow encode. */
    @Test
    fun `a slow encoder does not push an AV1 source to HEVC`() {
        val slow = CodecChoice.MeasuredSpeed(realtimeMultiple = 0.2, samples = 50)
        val choice = encode(CodecChoice.choose(clip(codec = "av01"), both, proWithAv1, Tier.PRO, slow))
        assertEquals(VideoCodec.AV1, choice.codec)
    }

    // ----------------------------------------------------------------- no path

    @Test
    fun `a device with no hardware encoder skips the file`() {
        val choice = CodecChoice.choose(clip(), CodecCaps(), proWithAv1, Tier.PRO)
        assertEquals(SkipReason.NO_HARDWARE_ENCODER, assertIs<CodecChoice.Choice.Skip>(choice).reason)
    }

    /** A device with only AV1, and a user who may use it, is a valid path. */
    @Test
    fun `an AV1-only device encodes AV1`() {
        val av1Only = CodecCaps(
            av1 = EncoderCaps(hardware = true, maxWidth = 3840, maxHeight = 2160, maxFps = 60.0),
        )
        assertEquals(
            VideoCodec.AV1,
            encode(CodecChoice.choose(clip(), av1Only, proWithAv1, Tier.PRO)).codec,
        )
    }

    /**
     * And an AV1-only device with a free user has no path at all, because AV1 is Pro. The
     * file is skipped with a reason rather than encoded in a format the user did not buy.
     */
    @Test
    fun `an AV1-only device offers a free user nothing`() {
        val av1Only = CodecCaps(
            av1 = EncoderCaps(hardware = true, maxWidth = 3840, maxHeight = 2160, maxFps = 60.0),
        )
        val choice = CodecChoice.choose(clip(), av1Only, Settings(), Tier.FREE)
        assertEquals(SkipReason.NO_HARDWARE_ENCODER, assertIs<CodecChoice.Choice.Skip>(choice).reason)
    }
}
