package app.trimgallery.core.model

/** BUILD.md § 9: Standard targets VMAF 95; Compact targets 90 and warns. */
enum class QualityTarget(val vmaf: Int) { STANDARD(95), COMPACT(90) }

enum class PhotoFormat { JPEG, HEIC }

/** ARCHITECTURE.md § 12. One shared DataStore, identical on both platforms. */
data class Settings(
    val qualityTarget: QualityTarget = QualityTarget.STANDARD,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val photoReversible: Boolean = false,
    val nightlyCapMinutes: Int = DEFAULT_CAP_MINUTES,
    val undoRetentionDays: Int = DEFAULT_RETENTION_DAYS,
    val allowAv1: Boolean = false,
    val carefulVerify: Boolean = false,
    val startWhenFull: Boolean = true,
    val keepWorkingWhileUsing: Boolean = false,
    val faceClusteringEnabled: Boolean = true,
    val stopByTime: String? = null,
) {
    companion object {
        const val DEFAULT_CAP_MINUTES = 60
        const val DEFAULT_RETENTION_DAYS = 30
    }
}

/**
 * Why a night stopped (SCHEMA.md `run_session.stop_reason`).
 *
 * Recorded because "it stopped" and "it finished" look identical in a total, and the
 * History screen has to be able to say which (USER_JOURNEY.md § 14).
 */
enum class StopReason { UNPLUGGED, FOREGROUND, THERMAL, CAP, STORAGE, STOP_BY, COMPLETE, CAP_FREE_TIER }

/** One night's work, for the Space screen and the morning card (SCHEMA.md `run_session`). */
data class RunSession(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val stopReason: StopReason? = null,
    val filesDone: Int = 0,
    val filesSkipped: Int = 0,
    val filesFailed: Int = 0,
    val bytesFreed: Long = 0,
    val minutesWorked: Double = 0.0,
    val energyWh: Double = 0.0,
    /** How often the pass stood down for heat; shown in History, never as an alarm. */
    val thermalPauses: Int = 0,
    val seen: Boolean = false,
)
