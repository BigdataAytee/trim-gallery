package app.trimgallery.engine

import app.trimgallery.core.model.MediaRef

/** Milliseconds. Named so signatures say what they mean. */
typealias Ms = Long

/** A file the app owns and may freely write or delete. Never a user's file. */
data class TempFile(val path: String)

/**
 * A decoded window of planar YUV, held in memory.
 *
 * ARCHITECTURE.md § 10: native metric functions take buffers, never paths. Windows are
 * decoded once and reused across every probe (PROJECT.md § Speed).
 */
class YuvWindow(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
)

/** A decoded still, for the photo path. */
class Image(val width: Int, val height: Int, val rgba: ByteArray)

/** One candidate encoder configuration in the search (milestone 3). */
data class Setting(val bitrate: Int, val mode: BitrateMode = BitrateMode.VBR, val cq: Int? = null)

enum class BitrateMode { VBR, CQ }

/** What the encoder should produce. Audio is always passed through, never re-encoded. */
data class EncodeSpec(
    val codec: VideoCodec,
    val setting: Setting,
    val width: Int,
    val height: Int,
    val fps: Double,
    val gopSeconds: Float = DEFAULT_GOP_SECONDS,
) {
    companion object {
        const val DEFAULT_GOP_SECONDS = 2f
    }
}

enum class VideoCodec { HEVC, AV1 }

/**
 * One point on `MediaCodecInfo.VideoCapabilities.getSupportedPerformancePoints()`.
 *
 * BUILD.md § 10: *"Check `getSupportedPerformancePoints()`; never request beyond advertised
 * throughput."* The rule matters most for AV1, where phone encoders routinely advertise 4K
 * at 30 fps while their HEVC sibling does 4K at 60 — asking for more does not fail cleanly,
 * it produces an encode that runs at a fraction of real time and eats the night.
 */
data class PerformancePoint(val width: Int, val height: Int, val fps: Int) {
    /**
     * Whether this point covers a frame of the given size and rate.
     *
     * Compared by area rather than by width and height separately, which is how the
     * platform documents it: a point advertised as 1920×1080 covers 1080×1920, because a
     * portrait clip is the same number of macroblocks turned on its side.
     */
    fun covers(width: Int, height: Int, fps: Double): Boolean {
        val area = width.toLong() * height.toLong()
        return area <= this.width.toLong() * this.height.toLong() && fps <= this.fps + FPS_SLACK
    }

    private companion object {
        /**
         * Containers report 29.97 as 30 and 23.976 as 24 often enough that an exact
         * comparison would refuse the commonest frame rates there are.
         */
        const val FPS_SLACK = 0.5
    }
}

/**
 * What one encoder on this device can do.
 *
 * Per encoder rather than per device because HEVC and AV1 differ, and the difference is not
 * cosmetic: on most phones with an AV1 encoder at all, its ceiling is lower than the HEVC
 * one. A single set of limits taken from HEVC — which is what this was until milestone 12 —
 * means an AV1 spec is checked against the wrong encoder's ceiling and passes when it
 * should not.
 */
data class EncoderCaps(
    val hardware: Boolean = false,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFps: Double = 0.0,
    val cqSupported: Boolean = false,
    /**
     * What the encoder says it can sustain, or empty where it does not say.
     *
     * Empty is not "anything": it is "no information", and the caller falls back to the
     * width, height and rate limits above. Treating silence as permission is how a night
     * ends up spending four hours on one clip.
     */
    val performancePoints: List<PerformancePoint> = emptyList(),
) {
    /** Whether a frame this size and this fast is within what the encoder advertises. */
    fun canSustain(width: Int, height: Int, fps: Double): Boolean {
        if (!hardware) return false
        if (width > maxWidth || height > maxHeight || fps > maxFps + 0.5) return false
        if (performancePoints.isEmpty()) return true
        return performancePoints.any { it.covers(width, height, fps) }
    }
}

/**
 * What the device can actually do, queried before anything is configured.
 *
 * ARCHITECTURE.md § 13: pre-check caps, fall back to VBR or a lower level — never to
 * software.
 */
data class CodecCaps(val hevc: EncoderCaps = EncoderCaps(), val av1: EncoderCaps = EncoderCaps()) {
    fun forCodec(codec: VideoCodec): EncoderCaps = when (codec) {
        VideoCodec.HEVC -> hevc
        VideoCodec.AV1 -> av1
    }

    /** True when the device can encode this spec in hardware at the rate asked for. */
    fun supports(spec: EncodeSpec): Boolean {
        val caps = forCodec(spec.codec)
        val modeOk = spec.setting.mode != BitrateMode.CQ || caps.cqSupported
        return modeOk && caps.canSustain(spec.width, spec.height, spec.fps)
    }

    /**
     * Whether *any* hardware encoder on this device could take a frame this size.
     *
     * Triage's question, and deliberately the weaker one: which codec a file ends up in is
     * settled later, by `CodecChoice`, once the settings and the tier are known.
     */
    fun anyCanSustain(width: Int, height: Int, fps: Double): Boolean =
        hevc.canSustain(width, height, fps) || av1.canSustain(width, height, fps)
}

/** The result of a full-file encode. */
sealed interface EncodeOutcome {
    data class Success(
        val output: TempFile,
        val bytes: Long,
        val durationMs: Ms,
        val videoMimeType: String?,
        val audioMimeType: String?,
        val elapsedMs: Ms,
    ) : EncodeOutcome

    /**
     * No hardware encoder could do the job.
     *
     * BUILD.md § 2.2 — the file is skipped, never encoded in software.
     */
    data object NoHardwareEncoder : EncodeOutcome

    /** The encoder was reclaimed or interrupted; retry per ARCHITECTURE.md § 13. */
    data class Interrupted(val reason: String) : EncodeOutcome

    data class Failed(val reason: String) : EncodeOutcome
}

/**
 * What re-opening an encoded output actually reports.
 *
 * Every field here has caught a real class of bug elsewhere: a truncated mux reports a
 * short duration, a dropped audio track means the passthrough silently failed, and a
 * zero-byte file opens as nothing at all.
 */
data class ProbedOutput(val durationMs: Ms, val hasVideo: Boolean, val hasAudio: Boolean, val sizeBytes: Long)

/**
 * Everything triage needs, read from a file's header (BUILD.md § 5).
 *
 * The format flags are here rather than inferred from the extension because that is the
 * only place the truth is: an HDR clip and an SDR clip are both `.mp4`, and BUILD.md § 2.5
 * requires the first to be left alone.
 */
data class ContainerFacts(
    val codec: String?,
    val width: Int,
    val height: Int,
    val fps: Double?,
    val bitrate: Long?,
    val durationMs: Ms?,
    val hasAudio: Boolean = false,
    val flags: app.trimgallery.core.model.MediaFlags = app.trimgallery.core.model.MediaFlags(),
    val cameraModel: String? = null,
    val takenAtEpochMs: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /**
     * The encoder that wrote the file, from the MP4 `udta` box where a camera left one.
     *
     * BUILD.md § 5 asks for it in the triage paragraph, immediately before "Predict", and
     * that is what it is for: a file with no camera model but a recognisable encoder is its
     * own predictor family rather than being lumped into "unknown" with every other
     * metadata-less file, which would poison a prediction that is otherwise reliable.
     */
    val writer: String? = null,
)

/** Metadata read from the container without decoding a frame (BUILD.md § 5, triage). */
data class Stat(val size: Long, val mtime: Long, val exists: Boolean)

/** A read-only handle to a user's file. There is deliberately no write counterpart. */
interface Source : AutoCloseable {
    fun read(into: ByteArray, offset: Int, length: Int): Int
}

/**
 * Everything the platform `Replacer` needs to swap one file for another.
 *
 * Carries the snapshot taken before the encode so the Replacer can refuse the swap if
 * the user edited the file in the meantime (ARCHITECTURE.md § 13).
 */
data class ReplacePlan(
    val original: MediaRef,
    /**
     * The `MediaItem` row this replaces.
     *
     * Not in the ARCHITECTURE.md § 5 sketch (recorded in PROJECT.md): the § 7 contract
     * ends by writing an `UndoEntry`, and an undo row that cannot name the item it came
     * from is an original nobody can restore.
     */
    val mediaId: String,
    val replacement: TempFile,
    val expectedSize: Long,
    val expectedMtime: Long,
    val undoLocation: app.trimgallery.core.model.UndoLocation,
)

/**
 * Adding a file to a granted folder without replacing anything (USER_JOURNEY.md § 11,
 * *"Save (new copy, original kept)"*; and "Keep both" after a Compress now).
 *
 * It exists because `Replacer` is the only component allowed to write to the user's library
 * (ARCHITECTURE.md § 14, enforced by a build guard), and that rule has to cover *adding* a
 * file as much as swapping one. Without this, the editor's Save would need a second writer,
 * and the guard would either fail the build or be weakened until it did not.
 *
 * There is deliberately no original, no snapshot and no undo entry: nothing is at risk, so
 * none of the machinery that protects an original applies. A failed add leaves the folder
 * as it was.
 */
data class NewCopyPlan(
    /** The folder to write into, taken from the item the copy came from. */
    val folder: MediaRef,
    /**
     * The name to aim for. The platform makes it unique — SAF appends "(1)" and PhotoKit
     * does not use names at all — so callers must read the result rather than assume this.
     */
    val preferredName: String,
    val content: TempFile,
    /**
     * The file whose date, GPS and camera details the copy inherits, or null for none.
     *
     * An edited copy of a photograph is the same photograph: it belongs on the same day, in
     * the same place, in the same album. A copy that lands under today's date has been
     * quietly filed somewhere the user will not look for it.
     */
    val inheritMetadataFrom: MediaRef? = null,
)

sealed interface NewCopyResult {
    data class Added(val ref: MediaRef, val name: String, val size: Long) : NewCopyResult

    /** Nothing was written. */
    data class Failed(val reason: String) : NewCopyResult
}

sealed interface ReplaceResult {
    data class Replaced(val undoRef: MediaRef, val newSize: Long) : ReplaceResult

    /** Size or mtime moved while the encode ran: the user edited the file. */
    data object SourceChanged : ReplaceResult

    /** Every completed step was rolled back; the original is untouched. */
    data class RolledBack(val reason: String) : ReplaceResult
}

/** Why the night pass may not run, in the order ARCHITECTURE.md § 9 fixes. */
sealed interface GuardResult {
    data object Proceed : GuardResult
    data class Pause(val reason: PauseReason) : GuardResult
    data class Stop(val reason: PauseReason) : GuardResult
}

/**
 * Why the pass stood down, in the order ARCHITECTURE.md § 9 evaluates them.
 *
 * Every one of these is shown to the user eventually — in History, or as "Paused for heat"
 * — so the distinctions here are the distinctions the user is told about. In particular
 * [CAP_REACHED] and [FREE_TIER_CAP] are deliberately separate: one means tonight's minutes
 * are spent and work resumes tomorrow, the other means the month's 3 GB are spent and Pro
 * removes the limit (MONETIZATION.md § Conversion moments). Collapsing them would either
 * nag a user who is not capped or fail to offer Pro to one who is.
 */
enum class PauseReason {
    FOREGROUND,
    NOT_CHARGING,
    BATTERY_NOT_FULL,
    THERMAL,
    STOP_BY_TIME,
    STORAGE_LOW,
    CAP_REACHED,
    FREE_TIER_CAP,
}

/** What the OS scheduler should wait for before waking the night pass. */
data class NightConstraints(
    val requiresCharging: Boolean = true,
    val requiresIdle: Boolean = true,
    val requiresStorageNotLow: Boolean = true,
    val requiresBatteryFull: Boolean = true,
)
