package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.SkipReason
import app.trimgallery.engine.CodecCaps
import app.trimgallery.engine.EncoderCaps
import app.trimgallery.engine.PerformancePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Triage is metadata-only (BUILD.md § 5), so every case here is a plain data object. */
class TriagerTest {

    private fun video(
        codec: String,
        width: Int = 1920,
        height: Int = 1080,
        bitrate: Long,
        size: Long = 100_000_000,
        duration: Long = 60_000,
        flags: MediaFlags = MediaFlags(),
    ) = MediaItem(
        id = "1",
        platformRef = MediaRef("ref"),
        name = "clip.mp4",
        kind = MediaKind.VIDEO,
        codec = codec,
        width = width,
        height = height,
        fps = 30.0,
        bitrate = bitrate,
        size = size,
        duration = duration,
        takenAt = null,
        location = null,
        cameraModel = null,
        flags = flags,
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    private fun photo(codec: String, size: Long, flags: MediaFlags = MediaFlags()) = MediaItem(
        id = "2",
        platformRef = MediaRef("ref"),
        name = "photo.jpg",
        kind = MediaKind.PHOTO,
        codec = codec,
        width = 4032,
        height = 3024,
        fps = null,
        bitrate = null,
        size = size,
        duration = null,
        takenAt = null,
        location = null,
        cameraModel = null,
        flags = flags,
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    // ------------------------------------------------------------------ video

    @Test
    fun `h264 is always a candidate whatever its bitrate`() {
        assertIs<Triager.Verdict.Candidate>(Triager.triage(video("avc1", bitrate = 2_000_000)))
        assertIs<Triager.Verdict.Candidate>(Triager.triage(video("h264", bitrate = 40_000_000)))
    }

    @Test
    fun `hevc at 1080p is a candidate above about 12 Mbps and skipped below`() {
        // BUILD.md § 5: "HEVC above ~12 Mbps at 1080p".
        assertIs<Triager.Verdict.Candidate>(Triager.triage(video("hevc", bitrate = 14_000_000)))
        val skip = Triager.triage(video("hevc", bitrate = 8_000_000))
        assertEquals(SkipReason.ALREADY_EFFICIENT, assertIs<Triager.Verdict.Skip>(skip).reason)
    }

    @Test
    fun `the hevc threshold scales to 4K rather than staying at the 1080p number`() {
        // BUILD.md § 5: "~30 Mbps at 4K". A 20 Mbps 4K file is efficient even though the
        // same bitrate at 1080p would not be.
        val fourK = video("hevc", width = 3840, height = 2160, bitrate = 20_000_000)
        assertEquals(SkipReason.ALREADY_EFFICIENT, assertIs<Triager.Verdict.Skip>(Triager.triage(fourK)).reason)

        val fatFourK = video("hevc", width = 3840, height = 2160, bitrate = 60_000_000)
        assertIs<Triager.Verdict.Candidate>(Triager.triage(fatFourK))
    }

    @Test
    fun `av1 uses its own lower threshold`() {
        assertIs<Triager.Verdict.Candidate>(Triager.triage(video("av01", bitrate = 12_000_000)))
        val skip = Triager.triage(video("av01", bitrate = 5_000_000))
        assertEquals(SkipReason.ALREADY_EFFICIENT, assertIs<Triager.Verdict.Skip>(skip).reason)
    }

    @Test
    fun `an unknown codec is skipped rather than attempted`() {
        val skip = Triager.triage(video("vp9", bitrate = 30_000_000))
        assertEquals(SkipReason.UNSUPPORTED_CODEC, assertIs<Triager.Verdict.Skip>(skip).reason)
    }

    @Test
    fun `a very short clip is not worth a probe cycle`() {
        val skip = Triager.triage(video("avc1", bitrate = 20_000_000, duration = 400))
        assertEquals(SkipReason.TOO_SMALL, assertIs<Triager.Verdict.Skip>(skip).reason)
    }

    @Test
    fun `missing bitrate or duration is skipped, never guessed`() {
        val noBitrate = video("avc1", bitrate = 0).copy(bitrate = null)
        assertEquals(SkipReason.UNSUPPORTED_CODEC, assertIs<Triager.Verdict.Skip>(Triager.triage(noBitrate)).reason)

        val noDuration = video("avc1", bitrate = 20_000_000).copy(duration = null)
        assertEquals(SkipReason.UNSUPPORTED_CODEC, assertIs<Triager.Verdict.Skip>(Triager.triage(noDuration)).reason)
    }

    // ------------------------------------------------------- format exclusions

    @Test
    fun `BUILD md section 2 point 5 formats are skipped whatever the bitrate`() {
        val cases = mapOf(
            MediaFlags(hdr = true) to SkipReason.HDR,
            MediaFlags(motionPhoto = true) to SkipReason.MOTION_PHOTO,
            MediaFlags(ultraHdr = true) to SkipReason.ULTRA_HDR,
            MediaFlags(livePhoto = true) to SkipReason.LIVE_PHOTO,
            MediaFlags(raw = true) to SkipReason.RAW,
            MediaFlags(inCloudOnly = true) to SkipReason.IN_CLOUD_ONLY,
        )
        cases.forEach { (flags, expected) ->
            // A fat H.264 file, which would otherwise be the most attractive candidate.
            val item = video("avc1", bitrate = 50_000_000, flags = flags)
            val verdict = Triager.triage(item)
            assertEquals(expected, assertIs<Triager.Verdict.Skip>(verdict).reason, "flags=$flags")
        }
    }

    // ------------------------------------------------------------------ photo

    @Test
    fun `jpegs above 500 KB are candidates and smaller ones are skipped`() {
        assertIs<Triager.Verdict.Candidate>(Triager.triage(photo("jpeg", size = 4_000_000)))
        val skip = Triager.triage(photo("jpeg", size = 200_000))
        assertEquals(SkipReason.TOO_SMALL, assertIs<Triager.Verdict.Skip>(skip).reason)
    }

    @Test
    fun `already efficient still formats are left alone`() {
        listOf("heic", "webp", "avif").forEach { codec ->
            val skip = Triager.triage(photo(codec, size = 4_000_000))
            assertEquals(SkipReason.ALREADY_EFFICIENT, assertIs<Triager.Verdict.Skip>(skip).reason, codec)
        }
    }

    // ------------------------------------------------------------------ queue

    @Test
    fun `estimated saving orders the queue largest first`() {
        // BUILD.md § 5: "Largest potential saving first."
        val big = Triager.triage(video("avc1", bitrate = 20_000_000, size = 900_000_000))
        val small = Triager.triage(video("avc1", bitrate = 20_000_000, size = 40_000_000))
        assertTrue(
            assertIs<Triager.Verdict.Candidate>(big).estimatedSaving >
                assertIs<Triager.Verdict.Candidate>(small).estimatedSaving,
        )
    }

    @Test
    fun `a png needs no quality gate, only a size one`() {
        // The repack is lossless, so there is no quality question — but a repack that
        // saves nothing still costs a write to the user's storage.
        val png = photo("png", size = 3_000_000).copy(kind = MediaKind.PNG)
        assertIs<Triager.Verdict.Candidate>(Triager.triage(png))
        val tiny = photo("png", size = 20_000).copy(kind = MediaKind.PNG)
        assertEquals(SkipReason.TOO_SMALL, assertIs<Triager.Verdict.Skip>(Triager.triage(tiny)).reason)
    }

    // -------------------------------------------------- milestone 6 additions

    @Test
    fun `a file this app already optimised is never optimised again`() {
        // The one rule that prevents generational loss. Every re-encode targets VMAF 95
        // against whatever it is given, so a second pass measures quality against an
        // already-lossy copy — and two nights of that is visible.
        val ours = video("hvc1", bitrate = 30_000_000, size = 400_000_000)
            .copy(optimisedAt = 1_700_000_000_000)
        assertEquals(
            SkipReason.ALREADY_EFFICIENT,
            assertIs<Triager.Verdict.Skip>(Triager.triage(ours)).reason,
        )
        // Without the marker the very same file is exactly what triage looks for, which is
        // why the check cannot be left to the bitrate rules.
        assertIs<Triager.Verdict.Candidate>(Triager.triage(ours.copy(optimisedAt = null)))
    }

    @Test
    fun `a file the hardware cannot encode is skipped with a reason, not attempted`() {
        // ARCHITECTURE.md § 13: pre-check caps. Failing at encode time would cost the whole
        // probe and search first, and tell the user nothing.
        val eightK = video("avc1", bitrate = 80_000_000, size = 900_000_000)
            .copy(width = 7680, height = 4320)
        val caps = CodecCaps(hevc = hevcCaps())
        assertEquals(
            SkipReason.NO_HARDWARE_ENCODER,
            assertIs<Triager.Verdict.Skip>(Triager.triage(eightK, caps)).reason,
        )
        // The same file is a candidate on a device that can manage it.
        assertIs<Triager.Verdict.Candidate>(
            Triager.triage(eightK, CodecCaps(hevc = hevcCaps(maxWidth = 7680, maxHeight = 4320))),
        )
    }

    @Test
    fun `a frame rate beyond the encoder is a skip`() {
        val slowMotion = video("avc1", bitrate = 80_000_000, size = 400_000_000).copy(fps = 240.0)
        val caps = CodecCaps(hevc = hevcCaps())
        assertEquals(
            SkipReason.NO_HARDWARE_ENCODER,
            assertIs<Triager.Verdict.Skip>(Triager.triage(slowMotion, caps)).reason,
        )
    }

    @Test
    fun `a device with no hardware encoder at all skips every video`() {
        // BUILD.md rule 2: skip the file, never fall back to software.
        val caps = CodecCaps()
        assertEquals(
            SkipReason.NO_HARDWARE_ENCODER,
            assertIs<Triager.Verdict.Skip>(
                Triager.triage(video("avc1", bitrate = 20_000_000, size = 400_000_000), caps),
            ).reason,
        )
    }

    /**
     * The defect this split fixed: one set of limits taken from the HEVC encoder was
     * applied to both, so a 4K60 clip looked encodable on a device whose AV1 encoder tops
     * out at 4K30. Triage asks the weaker question — is there *any* path — so an AV1-only
     * device with the lower ceiling correctly skips it, and a device with HEVC does not.
     */
    @Test
    fun `each encoder is checked against its own ceiling`() {
        val fourKSixty = video("avc1", bitrate = 80_000_000, size = 900_000_000)
            .copy(width = 3840, height = 2160, fps = 60.0)

        val av1Only = CodecCaps(av1 = EncoderCaps(hardware = true, maxWidth = 3840, maxHeight = 2160, maxFps = 30.0))
        assertEquals(
            SkipReason.NO_HARDWARE_ENCODER,
            assertIs<Triager.Verdict.Skip>(Triager.triage(fourKSixty, av1Only)).reason,
        )

        assertIs<Triager.Verdict.Candidate>(Triager.triage(fourKSixty, CodecCaps(hevc = hevcCaps())))
    }

    /** BUILD.md § 10: never request beyond advertised throughput. */
    @Test
    fun `an encoder's own performance points bound it`() {
        val fourKSixty = video("avc1", bitrate = 80_000_000, size = 900_000_000)
            .copy(width = 3840, height = 2160, fps = 60.0)
        val advertised = hevcCaps().copy(
            performancePoints = listOf(PerformancePoint(3840, 2160, 30), PerformancePoint(1920, 1080, 60)),
        )
        assertEquals(
            SkipReason.NO_HARDWARE_ENCODER,
            assertIs<Triager.Verdict.Skip>(Triager.triage(fourKSixty, CodecCaps(hevc = advertised))).reason,
        )

        val fourKThirty = fourKSixty.copy(fps = 30.0)
        assertIs<Triager.Verdict.Candidate>(Triager.triage(fourKThirty, CodecCaps(hevc = advertised)))
    }

    /** Silence is "no information", not "no limit". */
    @Test
    fun `an encoder that lists no performance points falls back to its bounds`() {
        val fourKSixty = video("avc1", bitrate = 80_000_000, size = 900_000_000)
            .copy(width = 3840, height = 2160, fps = 60.0)
        assertIs<Triager.Verdict.Candidate>(Triager.triage(fourKSixty, CodecCaps(hevc = hevcCaps())))
    }

    private fun hevcCaps(maxWidth: Int = 3840, maxHeight: Int = 2160) = EncoderCaps(
        hardware = true,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        maxFps = 60.0,
        cqSupported = true,
    )

    @Test
    fun `unknown capabilities do not block triage`() {
        // Caps are queried once per device; a library scanned before that answer arrives
        // should still be triaged rather than reported as unsupported.
        assertIs<Triager.Verdict.Candidate>(
            Triager.triage(video("avc1", bitrate = 20_000_000, size = 400_000_000), caps = null),
        )
    }

    @Test
    fun `a saving too small to notice is not worth a night's battery`() {
        // BUILD.md rule 5 says to skip files that will not shrink. In practice that means
        // "will shrink by an amount nobody would notice", and a queue full of those pushes
        // the videos that would free gigabytes past the nightly cap.
        val tiny = video("avc1", bitrate = 20_000_000, size = 8_000_000)
        assertEquals(
            SkipReason.WOULD_NOT_SHRINK,
            assertIs<Triager.Verdict.Skip>(Triager.triage(tiny)).reason,
        )
        assertIs<Triager.Verdict.Candidate>(
            Triager.triage(video("avc1", bitrate = 20_000_000, size = 40_000_000)),
        )
    }

    @Test
    fun `photos are exempt from the saving floor, because they cost milliseconds`() {
        // A jpegli pass is not a probe cycle and a full encode (BUILD.md § 5).
        val small = photo("jpeg", size = 600_000)
        val verdict = assertIs<Triager.Verdict.Candidate>(Triager.triage(small))
        assertTrue(verdict.estimatedSaving < Triager.MIN_WORTHWHILE_SAVING_BYTES)
    }
}
