package app.trimgallery.core.domain.field

import app.trimgallery.core.model.Job
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoState

/**
 * The field test's arithmetic (BUILD.md § 13.13: *"measure GB/hour and Wh/GB"*).
 *
 * BUILD.md § 14 says what to log; this is what the log adds up to. It is shared, tested
 * code rather than a spreadsheet for one reason: the same numbers appear in three places
 * that must not disagree — the Space screen the user reads, the diagnostics file they can
 * export, and the field-test write-up LAUNCH.md wants published. A number computed three
 * ways is three numbers.
 *
 * **Everything here is a summary of what happened, never a projection.** The alpha gate is
 * decided on it, so an optimistic denominator would be a gate that passes a build that
 * should not ship.
 */
object FieldMetrics {

    const val BYTES_PER_GB = 1024.0 * 1024 * 1024

    /** What one device produced over a run of nights. */
    data class Summary(
        val nights: Int,
        val filesDone: Int,
        val filesSkipped: Int,
        val filesFailed: Int,
        val bytesFreed: Long,
        val minutesWorked: Double,
        val energyWh: Double,
        val filesIndexed: Int,
        val duplicatesFound: Int,
        val thermalPauses: Int,
        /** Median of `1 - newSize/originalSize` over succeeded video jobs. */
        val medianVideoSaving: Double?,
        val medianPhotoSaving: Double?,
        /** Every VMAF the verifier recorded, ascending, for the distribution LAUNCH.md wants. */
        val vmafScores: List<Double>,
        val medianRealtimeMultiple: Double?,
        val restoreRate: Double?,
    ) {
        /**
         * Gigabytes freed per hour of *work*, not per hour of wall clock.
         *
         * The distinction is the whole measurement. A night that is plugged in for eight
         * hours and works for forty minutes has freed its gigabytes in forty minutes; the
         * other seven hours were the guards doing their job. Dividing by wall clock would
         * make a well-behaved build look slow and reward one that ignores the thermal gate.
         */
        val gbPerHour: Double?
            get() = if (minutesWorked <= 0) null else (bytesFreed / BYTES_PER_GB) / (minutesWorked / 60.0)

        /**
         * Watt-hours spent per gigabyte freed — the number that decides whether this app is
         * worth leaving switched on.
         *
         * Null rather than infinity when nothing was freed: a night that freed nothing has
         * no cost per gigabyte, and reporting one would put a divide-by-zero artefact into
         * a published figure.
         */
        val whPerGb: Double?
            get() {
                val gb = bytesFreed / BYTES_PER_GB
                return if (gb <= 0) null else energyWh / gb
            }

        /** Thermal stand-downs per night, for LAUNCH.md's *"zero thermal complaints"*. */
        val thermalPausesPerNight: Double?
            get() = if (nights <= 0) null else thermalPauses.toDouble() / nights

        val filesAttempted: Int get() = filesDone + filesSkipped + filesFailed

        val failureRate: Double?
            get() = if (filesAttempted <= 0) null else filesFailed.toDouble() / filesAttempted
    }

    /**
     * Summarises one device's nights.
     *
     * @param jobs every job row, of any state. Failed and skipped rows are counted but
     *   contribute no saving — including them in the median would report a device as saving
     *   less the more carefully it declined to touch files, which is backwards.
     * @param kindOf what each job's item was. Video and photo savings are reported apart
     *   because LAUNCH.md's gate is about video and a library of screenshots would otherwise
     *   drag the number it is checked against.
     * @param undo the undo rows, for the restore rate.
     *
     * [kindOf] is last so the ordinary call reads as a trailing lambda.
     */
    fun summarise(
        sessions: List<RunSession>,
        jobs: List<Job>,
        undo: List<UndoEntry> = emptyList(),
        kindOf: (Job) -> MediaKind?,
    ): Summary {
        val succeeded = jobs.filter { it.state == JobState.SUCCEEDED }

        return Summary(
            nights = sessions.size,
            filesDone = sessions.sumOf { it.filesDone },
            filesSkipped = sessions.sumOf { it.filesSkipped },
            filesFailed = sessions.sumOf { it.filesFailed },
            bytesFreed = sessions.sumOf { it.bytesFreed },
            minutesWorked = sessions.sumOf { it.minutesWorked },
            energyWh = sessions.sumOf { it.energyWh },
            filesIndexed = sessions.sumOf { it.filesIndexed },
            duplicatesFound = sessions.sumOf { it.duplicatesFound },
            thermalPauses = sessions.sumOf { it.thermalPauses },
            medianVideoSaving = median(
                succeeded.filter { kindOf(it) == MediaKind.VIDEO }.mapNotNull { it.savedFraction },
            ),
            medianPhotoSaving = median(
                succeeded.filter { kindOf(it) == MediaKind.PHOTO || kindOf(it) == MediaKind.PNG }
                    .mapNotNull { it.savedFraction },
            ),
            vmafScores = succeeded.mapNotNull { it.vmaf }.sorted(),
            medianRealtimeMultiple = median(succeeded.mapNotNull { it.realtimeMultiple }),
            restoreRate = restoreRate(undo),
        )
    }

    /**
     * The fraction of originals the user asked back for.
     *
     * LAUNCH.md's alpha gate is *"restore rate < 2%"*, and it is the honest measure of
     * whether the quality target is right: a user who cannot see the difference does not
     * restore. Counted over rows that reached a decision — an entry still sitting in the bin
     * has not been declined, it has not been looked at, and counting it as a success would
     * flatter the number for the whole retention period.
     */
    fun restoreRate(undo: List<UndoEntry>): Double? {
        val decided = undo.count { it.state == UndoState.RESTORED || it.state == UndoState.EXPIRED }
        if (decided == 0) return null
        return undo.count { it.state == UndoState.RESTORED }.toDouble() / decided
    }

    /**
     * The median, and deliberately not the mean.
     *
     * One 4K drone clip that compresses to a tenth would pull a mean saving up by several
     * points on its own. LAUNCH.md's gate is about what a typical file does, and the median
     * is the statistic that answers that question.
     */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** The nth percentile, for the VMAF distribution — the tail is what a reviewer notices. */
    fun percentile(values: List<Double>, percent: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = (percent / 100.0) * (sorted.size - 1)
        val low = rank.toInt()
        val high = (low + 1).coerceAtMost(sorted.size - 1)
        val weight = rank - low
        return sorted[low] * (1 - weight) + sorted[high] * weight
    }
}
