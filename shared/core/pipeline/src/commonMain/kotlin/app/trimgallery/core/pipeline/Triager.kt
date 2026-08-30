package app.trimgallery.core.pipeline

import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.SkipReason
import app.trimgallery.engine.CodecCaps

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
     * Below this, a saving is not worth spending a night on.
     *
     * A probe cycle plus a full encode costs real battery and real heat. Freeing two
     * megabytes is not worth either, and a queue full of such files pushes the videos that
     * would free gigabytes past the nightly cap (BUILD.md § 6).
     */
    const val MIN_WORTHWHILE_SAVING_BYTES = 5L * 1024L * 1024L

    /**
     * Expected size factor for a re-encode, used only to order the queue.
     *
     * PROJECT.md: *"Hardware HEVC is less efficient than x265; expect 30–45% on phone
     * H.264, not 50%."* The conservative end of that range is used so the queue does not
     * over-promise; the real number comes from the predictor once it has samples.
     */
    private const val EXPECTED_H264_FACTOR = 0.62
    private const val EXPECTED_EFFICIENT_FACTOR = 0.80

    /** oxipng on a screenshot. Lossless, so the win is smaller and entirely reliable. */
    private const val EXPECTED_PNG_FACTOR = 0.85

    /**
     * @param caps what this device's hardware can actually encode, or null when it has not
     *   been queried yet. ARCHITECTURE.md § 13: *"pre-check caps"*. Checking here rather
     *   than at encode time saves the whole probe and search on a file the phone was never
     *   going to be able to encode, and gives the user a reason instead of a failure.
     */
    fun triage(item: MediaItem, caps: CodecCaps? = null): Verdict {
        // Already ours. Every re-encode targets VMAF 95 against whatever it is given, so
        // optimising our own output measures quality against an already-lossy copy — and
        // two nights of that is visible. This is checked before anything else because no
        // later rule would notice: the file is, by construction, exactly the kind of file
        // the bitrate rules are looking for.
        if (item.optimisedAt != null) return Verdict.Skip(SkipReason.ALREADY_EFFICIENT)

        formatSkip(item.flags)?.let { return Verdict.Skip(it) }

        return when (item.kind) {
            MediaKind.VIDEO -> triageVideo(item, caps)
            MediaKind.PHOTO -> triagePhoto(item)
            MediaKind.PNG -> triagePng(item)
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

    private fun triageVideo(item: MediaItem, caps: CodecCaps?): Verdict {
        val duration = item.duration ?: return Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)
        if (duration < MIN_VIDEO_DURATION_MS) return Verdict.Skip(SkipReason.TOO_SMALL)

        val bitrate = item.bitrate ?: return Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)
        val megapixels = item.pixels / 1_000_000.0
        if (megapixels <= 0.0) return Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)

        val codec = item.codec?.lowercase() ?: return Verdict.Skip(SkipReason.UNSUPPORTED_CODEC)

        // BUILD.md rule 2: hardware only, never software. A file this device cannot encode
        // in hardware is skipped with a reason, not attempted and failed after an hour.
        if (caps != null && !canEncode(item, caps)) {
            return Verdict.Skip(SkipReason.NO_HARDWARE_ENCODER)
        }

        return when {
            // H.264 is always a candidate: it is the format the saving comes from.
            codec.isH264() -> candidate(item.size, EXPECTED_H264_FACTOR)

            codec.isHevc() -> {
                val threshold = HEVC_BITS_PER_MEGAPIXEL * megapixels
                if (bitrate > threshold) {
                    candidate(item.size, EXPECTED_EFFICIENT_FACTOR)
                } else {
                    Verdict.Skip(SkipReason.ALREADY_EFFICIENT)
                }
            }

            codec.isAv1() -> {
                val threshold = AV1_BITS_PER_MEGAPIXEL * megapixels
                if (bitrate > threshold) {
                    candidate(item.size, EXPECTED_EFFICIENT_FACTOR)
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
            // Photos are exempt from the worthwhile-saving floor: a jpegli pass costs
            // milliseconds, not a probe cycle and a full encode, so a small win is still
            // a win (BUILD.md § 5, "full binary search per file, milliseconds each").
            Verdict.Candidate(saving(item.size, EXPECTED_EFFICIENT_FACTOR))
        } else {
            Verdict.Skip(SkipReason.ALREADY_EFFICIENT)
        }
    }

    /**
     * PNG is a lossless repack, so there is no quality question to ask — but there is
     * still a size one.
     *
     * BUILD.md § 5 gives PNG no quality gate. oxipng typically wins 10–30% on a screenshot
     * and almost nothing on a PNG that was already optimised, and a repack that saves
     * nothing still costs a write to the user's storage. The same floor applies.
     */
    private fun triagePng(item: MediaItem): Verdict {
        if (item.size < MIN_PHOTO_BYTES) return Verdict.Skip(SkipReason.TOO_SMALL)
        return Verdict.Candidate(saving(item.size, EXPECTED_PNG_FACTOR))
    }

    /**
     * Whether this device can encode this file at all (ARCHITECTURE.md § 13).
     *
     * Resolution and frame rate only, against *either* encoder. Which codec a file ends
     * up in is `CodecChoice`'s question and needs the settings and the tier, neither of
     * which triage has — so triage asks the weaker one, "is there any hardware path for a
     * frame this size at all", and lets the choice settle the rest.
     */
    private fun canEncode(item: MediaItem, caps: CodecCaps): Boolean =
        caps.anyCanSustain(item.width, item.height, item.fps ?: 0.0)

    /**
     * A candidate, unless the saving is too small to be worth a night's battery.
     *
     * BUILD.md rule 5 says to skip files that will not shrink; this is the same rule with
     * a number on it, because "will not shrink" in practice means "will shrink by an
     * amount nobody would notice".
     */
    private fun candidate(size: Long, factor: Double): Verdict {
        val estimate = saving(size, factor)
        return if (estimate < MIN_WORTHWHILE_SAVING_BYTES) {
            Verdict.Skip(SkipReason.WOULD_NOT_SHRINK)
        } else {
            Verdict.Candidate(estimate)
        }
    }

    private fun saving(size: Long, factor: Double): Long =
        (size * (1.0 - factor)).toLong().coerceAtLeast(0)

    private fun String.isH264() = contains("avc") || contains("h264") || contains("h.264")
    private fun String.isHevc() = contains("hevc") || contains("h265") || contains("h.265") || contains("hvc")
    private fun String.isAv1() = contains("av01") || contains("av1")
    private fun String.isJpeg() = contains("jpeg") || contains("jpg")
}
