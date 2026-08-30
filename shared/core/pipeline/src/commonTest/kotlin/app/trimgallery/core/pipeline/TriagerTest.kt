package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.SkipReason
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
    fun `a png is always a candidate because the repack is lossless`() {
        val png = photo("png", size = 3_000_000).copy(kind = MediaKind.PNG)
        assertIs<Triager.Verdict.Candidate>(Triager.triage(png))
    }
}
