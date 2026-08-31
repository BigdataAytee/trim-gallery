package app.trimgallery.core.pipeline.photo

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.PhotoFormat
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason

/**
 * Which of the four still-image paths a file takes (BUILD.md § 5).
 *
 * > JPEG → JPEG via jpegli (default) or → HEIC via HeifWriter (setting) … Optional
 * > reversible mode: JPEG → JPEG XL lossless recompress … Screenshots / PNG: lossless
 * > repack with oxipng … PNG that is actually a photo → quality-gated lossy path.
 *
 * Separated from the step that runs them because the routing is where the user's settings
 * meet the file's format, and both change independently of the encoding itself.
 */
enum class PhotoRoute {
    /** Default. Lossy, gated on SSIMULACRA2. */
    JPEGLI,

    /** The "photo format: HEIC" setting. Lossy, same gate, smaller for the same quality. */
    HEIC,

    /**
     * Reversible mode: JPEG XL lossless recompress.
     *
     * The only path that is genuinely lossless — the original JPEG can be reconstructed
     * bit-for-bit — which is why it needs no quality gate and why PROJECT.md's honesty rule
     * about "visually lossless, not lossless" does not apply to it.
     */
    JXL_LOSSLESS,

    /** oxipng. Lossless by construction, so no gate (BUILD.md § 5). */
    PNG_REPACK,
}

object PhotoRouting {

    /**
     * Bytes per pixel above which a PNG is treated as a photograph rather than a screenshot.
     *
     * BUILD.md § 5 asks for *"PNG that is actually a photo → quality-gated lossy path"*, and
     * this is the cheapest signal that separates them: PNG's filters and DEFLATE do very
     * well on the flat colour and repeated glyphs of a screenshot and very badly on sensor
     * noise. A 1080p screenshot lands around 0.2–0.6 B/px; the same frame photographed and
     * saved as PNG lands around 2–3.
     *
     * One byte per pixel sits in the empty space between those populations. Erring high
     * costs nothing — a misjudged photo is merely repacked losslessly instead of being made
     * smaller — while erring low would run a lossy encoder over a screenshot, where
     * ringing around text is exactly what people notice.
     */
    const val PHOTO_BYTES_PER_PIXEL = 1.0

    /** What the file is, or why it is not our business. */
    sealed interface Decision {
        data class Take(val route: PhotoRoute) : Decision
        data class Skip(val reason: SkipReason) : Decision
    }

    fun decide(item: MediaItem, settings: Settings): Decision {
        // Triage has already excluded these (BUILD.md § 2.5), but the step is reachable
        // from "Compress now" too, where the user picked the file rather than triage.
        if (item.flags.ultraHdr) return Decision.Skip(SkipReason.ULTRA_HDR)
        if (item.flags.motionPhoto) return Decision.Skip(SkipReason.MOTION_PHOTO)
        if (item.flags.livePhoto) return Decision.Skip(SkipReason.LIVE_PHOTO)
        if (item.flags.raw) return Decision.Skip(SkipReason.RAW)

        return when (item.kind) {
            MediaKind.PNG -> Decision.Take(pngRoute(item, settings))
            MediaKind.PHOTO -> photoRoute(item, settings)
            MediaKind.VIDEO, MediaKind.FILE -> Decision.Skip(SkipReason.UNSUPPORTED_CODEC)
        }
    }

    private fun photoRoute(item: MediaItem, settings: Settings): Decision {
        val codec = item.codec?.lowercase().orEmpty()
        if (!isJpeg(codec)) {
            // HEIC, WebP and AVIF are already efficient; re-encoding them spends quality
            // the user cannot get back for a gain that does not justify it (BUILD.md § 5).
            return Decision.Skip(SkipReason.ALREADY_EFFICIENT)
        }

        // Reversible mode wins over the format setting. It is the stronger promise — the
        // original JPEG comes back bit-for-bit — and a user who asked for it did so
        // knowing it saves less.
        if (settings.photoReversible) return Decision.Take(PhotoRoute.JXL_LOSSLESS)

        return Decision.Take(
            when (settings.photoFormat) {
                PhotoFormat.JPEG -> PhotoRoute.JPEGLI
                PhotoFormat.HEIC -> PhotoRoute.HEIC
            },
        )
    }

    /**
     * A screenshot is repacked losslessly; a photograph saved as PNG goes through the gate.
     *
     * The lossy path for such a file is the format the user's setting asks for, not PNG:
     * there is no point making a smaller PNG of a photograph when a JPEG or HEIC of it is a
     * fraction of the size.
     */
    private fun pngRoute(item: MediaItem, settings: Settings): PhotoRoute {
        if (!looksPhotographic(item)) return PhotoRoute.PNG_REPACK
        return when (settings.photoFormat) {
            PhotoFormat.JPEG -> PhotoRoute.JPEGLI
            PhotoFormat.HEIC -> PhotoRoute.HEIC
        }
    }

    /** True when the file is dense enough per pixel to be sensor data rather than a UI. */
    fun looksPhotographic(item: MediaItem): Boolean {
        val pixels = item.pixels
        if (pixels <= 0) return false
        return item.size.toDouble() / pixels >= PHOTO_BYTES_PER_PIXEL
    }

    private fun isJpeg(codec: String) = codec.contains("jpeg") || codec.contains("jpg")
}
