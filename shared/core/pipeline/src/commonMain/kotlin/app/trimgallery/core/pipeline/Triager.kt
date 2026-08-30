package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.SkipReason

/**
 * Decides, from container metadata alone, whether a file is worth optimising.
 *
 * BUILD.md § 5: *"Triage (metadata only, no decode)."* Nothing here opens a file or
 * decodes a frame — triage runs over the whole library every night, so it has to be
 * cheap. Anything needing pixels belongs in the probe step (milestone 3).
 *
 * Pure Kotlin with no platform dependency, so it is unit tested on the JVM
 * (ARCHITECTURE.md § 14).
 */
object Triager {

    /** What triage decided, and why. */
    sealed interface Verdict {
        /** Worth optimising. [estimatedSaving] orders the queue, largest first. */
        data class Candidate(val estimatedSaving: Long) : Verdict

        data class Skip(val reason: SkipReason) : Verdict
    }

    /**
     * Bitrate above which an already-efficient codec is still worth re-encoding.
     *
     * BUILD.md § 5: *"HEVC above ~12 Mbps at 1080p / ~30 Mbps at 4K, or AV1 above
     * ~8 Mbps."* Expressed per megapixel so it scales to the resolutions between and
     * beyond those two points rather than special-casing two labels.
     */
    private const val HEVC_BITS_PER_MEGAPIXEL = 5_800_000.0 // ~12 Mbps at 1080p (2.07 MP)
    private const val AV1_BITS_PER_MEGAPIXEL = 3_900_000.0 // ~8 Mbps at 1080p

    /** Below this, the saving cannot pay for the work. BUILD.md § 5 for photos. */
    const val MIN_PHOTO_BYTES = 500L * 1024L

    /** Videos shorter than this are not worth a probe cycle. */
    const val MIN_VIDEO_DURATION_MS = 1_000L

    /**
     * Expected size factor for a re-encode, used only to order the queue.
     *
     * PROJECT.md: *"Hardware HEVC is less efficient than x265; expect 30–45% on phone
     * H.264, not 50%."* The conservative end of that range is used so the queue does not
     * over-promise; the real number comes from the predictor once it has samples.
     */
    private const val EXPECTED_H264_FACTOR = 0.62
    private const val EXPECTED_EFFICIENT_FACTOR = 0.80

    fun triage(item: MediaItem): Verdict {
        formatSkip(item.flags)?.let { return Verdict.Skip(it) }

        return when (item.kind) {
            MediaKind.VIDEO -> triageVideo(item)
            MediaKind.PHOTO -> triagePhoto(item)
            MediaKind.PNG -> Verdict.Candidate(estimatedSaving = 0) // lossless repack, always safe
            MediaKind.FILE -> Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)
        }
    }

    /**
     * Formats that are skipped whatever their bitrate.
     *
     * BUILD.md § 2.5: the metrics are not calibrated for these, and re-encoding destroys
     * embedded data — a gainmap, a paired video, a raw sensor read. Checked before
     * anything else so no later rule can accidentally admit one.
     */
    private fun formatSkip(flags: MediaFlags): SkipReason? = when {
        flags.inCloudOnly -> SkipReason.IN_CLOUD_ONLY
        flags.hdr -> SkipReason.HDR
        flags.motionPhoto -> SkipReason.MOTION_PHOTO
        flags.ultraHdr -> SkipReason.ULTRA_HDR
        flags.livePhoto -> SkipReason.LIVE_PHOTO
        flags.raw -> SkipReason.RAW
        else -> null
    }

    private fun triageVideo(item: MediaItem): Verdict {
        val duration = item.duration ?: return Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)
        if (duration < MIN_VIDEO_DURATION_MS) return Verdict.Skip(SkipReason.TOO_SMALL)

        val bitrate = item.bitrate ?: return Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)
        val megapixels = item.pixels / 1_000_000.0
        if (megapixels <= 0.0) return Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)

        val codec = item.codec?.lowercase() ?: return Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)

        return when {
            // H.264 is always a candidate: it is the format the saving comes from.
            codec.isH264() -> Verdict.Candidate(saving(item.size, EXPECTED_H264_FACTOR))

            codec.isHevc() -> {
                val threshold = HEVC_BITS_PER_MEGAPIXEL * megapixels
                if (bitrate > threshold) {
                    Verdict.Candidate(saving(item.size, EXPECTED_EFFICIENT_FACTOR))
                } else {
                    Verdict.Skip(SkipReason.ALREADY_EFFICIENT)
                }
            }

            codec.isAv1() -> {
                val threshold = AV1_BITS_PER_MEGAPIXEL * megapixels
                if (bitrate > threshold) {
                    Verdict.Candidate(saving(item.size, EXPECTED_EFFICIENT_FACTOR))
                } else {
                    Verdict.Skip(SkipReason.ALREADY_EFFICIENT)
                }
            }

            else -> Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)
        }
    }

    /**
     * BUILD.md § 5: *"Skip HEIC/WebP/AVIF, Motion Photos, Ultra HDR, anything < 500 KB."*
     * The already-efficient still formats are skipped because the gain does not justify
     * re-encoding a photo the user cannot get back.
     */
    private fun triagePhoto(item: MediaItem): Verdict {
        if (item.size < MIN_PHOTO_BYTES) return Verdict.Skip(SkipReason.TOO_SMALL)
        val codec = item.codec?.lowercase() ?: return Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)
        return if (codec.isJpeg()) {
            Verdict.Candidate(saving(item.size, EXPECTED_EFFICIENT_FACTOR))
        } else {
            Verdict.Skip(SkipReason.ALREADY_EFFICIENT)
        }
    }

    private fun saving(size: Long, factor: Double): Long =
        (size * (1.0 - factor)).toLong().coerceAtLeast(0)

    private fun String.isH264() = contains("avc") || contains("h264") || contains("h.264")
    private fun String.isHevc() = contains("hevc") || contains("h265") || contains("h.265") || contains("hvc")
    private fun String.isAv1() = contains("av01") || contains("av1")
    private fun String.isJpeg() = contains("jpeg") || contains("jpg")
}
