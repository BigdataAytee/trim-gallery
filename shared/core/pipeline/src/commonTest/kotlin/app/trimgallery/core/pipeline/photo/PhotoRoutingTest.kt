package app.trimgallery.core.pipeline.photo

import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.PhotoFormat
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhotoRoutingTest {

    private fun still(
        codec: String = "jpeg",
        kind: MediaKind = MediaKind.PHOTO,
        size: Long = 4_000_000,
        width: Int = 4032,
        height: Int = 3024,
        flags: MediaFlags = MediaFlags(),
    ) = MediaItem(
        id = "1",
        platformRef = MediaRef("ref"),
        name = "photo.jpg",
        kind = kind,
        codec = codec,
        width = width,
        height = height,
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

    private fun route(item: MediaItem, settings: Settings = Settings()): PhotoRoute =
        assertIs<PhotoRouting.Decision.Take>(PhotoRouting.decide(item, settings)).route

    private fun skip(item: MediaItem, settings: Settings = Settings()): SkipReason =
        assertIs<PhotoRouting.Decision.Skip>(PhotoRouting.decide(item, settings)).reason

    @Test
    fun `a jpeg goes through jpegli by default`() {
        assertEquals(PhotoRoute.JPEGLI, route(still()))
    }

    @Test
    fun `the photo format setting switches to HEIC`() {
        assertEquals(PhotoRoute.HEIC, route(still(), Settings(photoFormat = PhotoFormat.HEIC)))
    }

    @Test
    fun `reversible mode wins over the format setting`() {
        // It is the stronger promise — the original JPEG comes back bit for bit — and a
        // user who turned it on did so knowing it saves less.
        val reversible = Settings(photoReversible = true, photoFormat = PhotoFormat.HEIC)
        assertEquals(PhotoRoute.JXL_LOSSLESS, route(still(), reversible))
    }

    @Test
    fun `already efficient still formats are left alone`() {
        // BUILD.md § 5: re-encoding them spends quality the user cannot get back for a gain
        // that does not justify it.
        listOf("heic", "webp", "avif").forEach { codec ->
            assertEquals(SkipReason.ALREADY_EFFICIENT, skip(still(codec = codec)), codec)
        }
    }

    @Test
    fun `the formats BUILD md section 2 point 5 excludes are refused here too`() {
        // Triage already excludes them, but this step is reachable from "Compress now",
        // where the user picked the file rather than triage.
        assertEquals(SkipReason.ULTRA_HDR, skip(still(flags = MediaFlags(ultraHdr = true))))
        assertEquals(SkipReason.MOTION_PHOTO, skip(still(flags = MediaFlags(motionPhoto = true))))
        assertEquals(SkipReason.LIVE_PHOTO, skip(still(flags = MediaFlags(livePhoto = true))))
        assertEquals(SkipReason.RAW, skip(still(flags = MediaFlags(raw = true))))
    }

    @Test
    fun `a video never reaches the photo path`() {
        assertEquals(SkipReason.UNSUPPORTED_CODEC, skip(still(kind = MediaKind.VIDEO)))
    }

    // ------------------------------------------------------------------- PNG

    @Test
    fun `a screenshot is repacked losslessly`() {
        // 1080p at roughly 0.3 bytes per pixel: flat colour and repeated glyphs.
        val screenshot = still(codec = "png", kind = MediaKind.PNG, width = 1080, height = 2400, size = 700_000)
        assertEquals(PhotoRoute.PNG_REPACK, route(screenshot))
    }

    @Test
    fun `a photograph saved as PNG takes the gated lossy path`() {
        // BUILD.md § 5: "PNG that is actually a photo → quality-gated lossy path". There is
        // no point making a smaller PNG of a photograph when a JPEG of it is a fraction of
        // the size.
        val photoAsPng = still(codec = "png", kind = MediaKind.PNG, width = 4032, height = 3024, size = 30_000_000)
        assertEquals(PhotoRoute.JPEGLI, route(photoAsPng))
        assertEquals(PhotoRoute.HEIC, route(photoAsPng, Settings(photoFormat = PhotoFormat.HEIC)))
    }

    @Test
    fun `the photographic threshold sits between the two populations`() {
        // A screenshot lands around 0.2-0.6 B/px; the same frame photographed lands around
        // 2-3. Erring high merely repacks a photo losslessly; erring low would run a lossy
        // encoder over text, where ringing is exactly what people notice.
        val pixels = 1080L * 2400L
        val justBelow = still(kind = MediaKind.PNG, width = 1080, height = 2400, size = pixels - 1)
        val justAbove = still(kind = MediaKind.PNG, width = 1080, height = 2400, size = pixels)
        assertEquals(PhotoRoute.PNG_REPACK, route(justBelow))
        assertEquals(PhotoRoute.JPEGLI, route(justAbove))
    }

    @Test
    fun `a PNG with no recorded dimensions is repacked, never guessed at`() {
        val unknown = still(codec = "png", kind = MediaKind.PNG, width = 0, height = 0, size = 30_000_000)
        assertEquals(PhotoRoute.PNG_REPACK, route(unknown))
    }
}
