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
    companion object { const val DEFAULT_GOP_SECONDS = 2f }
}

enum class VideoCodec { HEVC, AV1 }

/**
 * What the device can actually do, queried before anything is configured.
 *
 * ARCHITECTURE.md § 13: pre-check caps, fall back to VBR or a lower level — never to
 * software.
 */
data class CodecCaps(
    val hardwareHevc: Boolean,
    val hardwareAv1: Boolean,
    val cqSupported: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Double,
) {
    /** True when the device can encode this spec in hardware at the rate asked for. */
    fun supports(spec: EncodeSpec): Boolean {
        val codecOk = when (spec.codec) {
            VideoCodec.HEVC -> hardwareHevc
            VideoCodec.AV1 -> hardwareAv1
        }
        val modeOk = spec.setting.mode != BitrateMode.CQ || cqSupported
        return codecOk && modeOk &&
            spec.width <= maxWidth && spec.height <= maxHeight && spec.fps <= maxFps
    }
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
    val replacement: TempFile,
    val expectedSize: Long,
    val expectedMtime: Long,
    val undoLocation: app.trimgallery.core.model.UndoLocation,
)

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

enum class PauseReason { FOREGROUND, NOT_CHARGING, BATTERY_NOT_FULL, THERMAL, STOP_BY_TIME, STORAGE_LOW, CAP_REACHED }

/** What the OS scheduler should wait for before waking the night pass. */
data class NightConstraints(
    val requiresCharging: Boolean = true,
    val requiresIdle: Boolean = true,
    val requiresStorageNotLow: Boolean = true,
    val requiresBatteryFull: Boolean = true,
)
