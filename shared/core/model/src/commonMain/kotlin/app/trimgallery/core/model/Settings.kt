package app.trimgallery.core.model

/** BUILD.md § 9: Standard targets VMAF 95; Compact targets 90 and warns. */
enum class QualityTarget(val vmaf: Int) { STANDARD(95), COMPACT(90) }

enum class PhotoFormat { JPEG, HEIC }

/** ARCHITECTURE.md § 12. One shared DataStore, identical on both platforms. */
data class Settings(
    /**
     * Whether the night pass may run at all — Home's on/off switch.
     *
     * Persisted rather than derived, because it is the user's *intent* and nothing else
     * records it. `NightPass.sync` schedules when a folder is granted and cancels when the
     * last one goes, so a switch that only called the scheduler would be turned back on by
     * the next grant change. A control that silently undoes itself is worse than no control.
     */
    val nightPassEnabled: Boolean = true,
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
    /**
     * Files indexed and duplicate groups found this night (BUILD.md § 14).
     *
     * Both are on that section's per-night list and neither was recorded until milestone 13
     * — a supplement to SCHEMA.md's `run_session` table, noted in PROJECT.md. They matter
     * beyond bookkeeping: BUILD.md § 7 puts indexing in the *same* pass as the optimisation,
     * and MONETIZATION.md promises indexing keeps running after the free cap is reached. A
     * night that optimised nothing because the cap was spent but indexed four hundred files
     * did work the user was promised, and with only [filesDone] to go on it looks like a
     * night that did nothing at all.
     */
    val filesIndexed: Int = 0,
    val duplicatesFound: Int = 0,
    val seen: Boolean = false,
)
