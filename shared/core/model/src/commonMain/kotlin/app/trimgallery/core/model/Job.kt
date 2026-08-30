package app.trimgallery.core.model

import kotlin.time.Instant

/** ARCHITECTURE.md § 9, `Job.state`; SCHEMA.md `job.state`. */
enum class JobState { QUEUED, PROBING, ENCODING, VERIFYING, REPLACING, SUCCEEDED, PAUSED, CANCELLED, FAILED }

/**
 * One attempt to optimise one item, and every number needed to explain the outcome
 * afterwards (BUILD.md § 14, "Metrics to log"; SCHEMA.md `job`).
 *
 * This row is what the History screen, the Skipped list and the field-test write-up are
 * all built from, so it records what was *measured* rather than what was intended: the
 * setting actually used, the score actually reached, the time actually taken.
 */
data class Job(
    val id: String,
    val mediaId: String,
    /** The night this belongs to; null for "Compress now" (USER_JOURNEY.md § 6). */
    val runSessionId: String? = null,
    val state: JobState = JobState.QUEUED,
    /** Where to resume. A night pass can be paused mid-file and picked up next time. */
    val stageBeforePause: JobState? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val engine: String? = null,
    val setting: String? = null,
    val probes: Int = 0,
    val xpsnr: Double? = null,
    val vmaf: Double? = null,
    /** SSIMULACRA2, for the photo path (BUILD.md § 5). */
    val ssim2: Double? = null,
    val originalSize: Long? = null,
    val newSize: Long? = null,
    val encodeMs: Long? = null,
    val verifyMs: Long? = null,
    /**
     * Encode speed as a multiple of real time.
     *
     * The number that decides whether a night is enough: at 4× real time an hour of
     * charging clears four hours of video, and BUILD.md § 6's nightly cap is set in
     * minutes, not files.
     */
    val realtimeMultiple: Double? = null,
    val thermalStart: Float? = null,
    val thermalEnd: Float? = null,
    val energyWh: Double? = null,
    /** Encode attempts spent, including step-ups (BUILD.md § 5, "max twice"). */
    val attempts: Int = 0,
    /** True for "Compress now", which is the one path allowed to run on battery. */
    val userInitiated: Boolean = false,
    val error: String? = null,
) {
    /** Bytes saved, or null while the job is still running. */
    val saved: Long?
        get() = if (originalSize != null && newSize != null) originalSize - newSize else null
}
