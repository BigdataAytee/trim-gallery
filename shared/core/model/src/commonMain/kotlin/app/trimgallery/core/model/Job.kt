package app.trimgallery.core.model

import kotlin.time.Instant

/** ARCHITECTURE.md § 9, `Job.state`. */
enum class JobState { QUEUED, PROBING, ENCODING, VERIFYING, REPLACING, SUCCEEDED, PAUSED, CANCELLED, FAILED }

/**
 * One attempt to optimise one item, and every number needed to explain the outcome
 * afterwards (BUILD.md § 14, "Metrics to log").
 */
data class Job(
    val id: Long,
    val mediaId: Long,
    val state: JobState = JobState.QUEUED,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val engine: String? = null,
    val setting: String? = null,
    val probes: Int = 0,
    val xpsnr: Double? = null,
    val vmaf: Double? = null,
    val originalSize: Long? = null,
    val newSize: Long? = null,
    val energyEstimate: Double? = null,
    val thermalStart: Float? = null,
    val thermalEnd: Float? = null,
    val error: String? = null,
) {
    /** Bytes saved, or null while the job is still running. */
    val saved: Long?
        get() = if (originalSize != null && newSize != null) originalSize - newSize else null
}
