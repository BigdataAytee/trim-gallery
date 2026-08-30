package app.trimgallery.core.domain.field

import app.trimgallery.core.model.Job
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldMetricsTest {

    private val gb = 1024L * 1024 * 1024

    private fun night(
        bytesFreed: Long = gb,
        minutes: Double = 60.0,
        wh: Double = 10.0,
        done: Int = 10,
        skipped: Int = 2,
        failed: Int = 0,
        indexed: Int = 0,
        duplicates: Int = 0,
        thermalPauses: Int = 0,
    ) = RunSession(
        id = "r",
        startedAt = 0,
        finishedAt = 1,
        filesDone = done,
        filesSkipped = skipped,
        filesFailed = failed,
        bytesFreed = bytesFreed,
        minutesWorked = minutes,
        energyWh = wh,
        thermalPauses = thermalPauses,
        filesIndexed = indexed,
        duplicatesFound = duplicates,
    )

    private fun job(
        id: String = "j",
        state: JobState = JobState.SUCCEEDED,
        original: Long? = 100,
        new: Long? = 60,
        vmaf: Double? = 96.0,
        realtime: Double? = 4.0,
    ) = Job(
        id = id,
        mediaId = "m$id",
        state = state,
        vmaf = vmaf,
        originalSize = original,
        newSize = new,
        realtimeMultiple = realtime,
    )

    private fun close(expected: Double, actual: Double?, tolerance: Double = 1e-6) {
        assertTrue(actual != null && abs(expected - actual) <= tolerance, "expected $expected, was $actual")
    }

    // ------------------------------------------------------------ the headline

    /**
     * Per hour of *work*, not of wall clock. A night plugged in for eight hours that worked
     * for forty minutes freed its gigabytes in forty minutes; the other seven hours were the
     * guards doing their job, and dividing by them would reward a build that ignored them.
     */
    @Test
    fun `GB per hour is measured against time actually worked`() {
        val summary = FieldMetrics.summarise(listOf(night(bytesFreed = 2 * gb, minutes = 30.0)), emptyList()) { null }
        close(4.0, summary.gbPerHour)
    }

    @Test
    fun `Wh per GB is the cost of the saving`() {
        val summary = FieldMetrics.summarise(listOf(night(bytesFreed = 2 * gb, wh = 9.0)), emptyList()) { null }
        close(4.5, summary.whPerGb)
    }

    /** A night that freed nothing has no cost per gigabyte; reporting one is a divide by zero. */
    @Test
    fun `a night that freed nothing reports no cost per gigabyte`() {
        val summary = FieldMetrics.summarise(listOf(night(bytesFreed = 0, wh = 5.0)), emptyList()) { null }
        assertNull(summary.whPerGb)
    }

    @Test
    fun `a night that never worked reports no rate`() {
        val summary = FieldMetrics.summarise(listOf(night(minutes = 0.0)), emptyList()) { null }
        assertNull(summary.gbPerHour)
    }

    @Test
    fun `nights add up`() {
        val summary = FieldMetrics.summarise(
            listOf(night(indexed = 100, duplicates = 4), night(indexed = 250, duplicates = 1)),
            emptyList(),
        ) { null }
        assertEquals(2, summary.nights)
        assertEquals(350, summary.filesIndexed)
        assertEquals(5, summary.duplicatesFound)
        assertEquals(20, summary.filesDone)
    }

    // ---------------------------------------------------------------- savings

    /** One drone clip that compresses to a tenth would carry a mean on its own. */
    @Test
    fun `the saving is a median, not a mean`() {
        val jobs = listOf(
            job("a", original = 100, new = 70),
            job("b", original = 100, new = 65),
            job("c", original = 100, new = 10),
        )
        val summary = FieldMetrics.summarise(listOf(night()), jobs) { MediaKind.VIDEO }
        close(0.35, summary.medianVideoSaving)
    }

    /**
     * Counting declined files as zero-saving would report a device as saving less the more
     * carefully it declined to touch things, which is backwards.
     */
    @Test
    fun `skipped and failed jobs do not drag the saving down`() {
        val jobs = listOf(
            job("a", original = 100, new = 60),
            job("b", state = JobState.FAILED, original = 100, new = null),
            job("c", state = JobState.CANCELLED, original = 100, new = 100),
        )
        val summary = FieldMetrics.summarise(listOf(night()), jobs) { MediaKind.VIDEO }
        close(0.40, summary.medianVideoSaving)
    }

    /** LAUNCH.md's gate is about video; a library of screenshots must not answer for it. */
    @Test
    fun `photos and videos are measured apart`() {
        val jobs = listOf(job("v", original = 100, new = 60), job("p", original = 100, new = 90))
        val kinds = mapOf("v" to MediaKind.VIDEO, "p" to MediaKind.PHOTO)
        val summary = FieldMetrics.summarise(listOf(night()), jobs) { kinds[it.id] }
        close(0.40, summary.medianVideoSaving)
        close(0.10, summary.medianPhotoSaving)
    }

    @Test
    fun `no jobs of a kind means no median for it`() {
        val summary = FieldMetrics.summarise(listOf(night()), emptyList()) { null }
        assertNull(summary.medianVideoSaving)
        assertNull(summary.medianPhotoSaving)
    }

    // ------------------------------------------------------------ restore rate

    /**
     * Counted over entries that reached a decision. One still sitting in the bin has not
     * been declined, it has not been looked at — counting it as a success would flatter the
     * number for the whole retention period.
     */
    @Test
    fun `the restore rate counts only entries that reached a decision`() {
        val undo = listOf(
            entry(UndoState.RESTORED),
            entry(UndoState.EXPIRED),
            entry(UndoState.EXPIRED),
            entry(UndoState.ACTIVE),
            entry(UndoState.ACTIVE),
        )
        close(1.0 / 3.0, FieldMetrics.restoreRate(undo))
    }

    @Test
    fun `nothing decided means no restore rate`() {
        assertNull(FieldMetrics.restoreRate(emptyList()))
        assertNull(FieldMetrics.restoreRate(listOf(entry(UndoState.ACTIVE))))
    }

    // -------------------------------------------------------------- statistics

    @Test
    fun `the median handles both parities`() {
        close(2.0, FieldMetrics.median(listOf(3.0, 1.0, 2.0)))
        close(2.5, FieldMetrics.median(listOf(1.0, 2.0, 3.0, 4.0)))
        assertNull(FieldMetrics.median(emptyList()))
    }

    /** The tail of the VMAF distribution is what a reviewer notices, not the middle. */
    @Test
    fun `percentiles interpolate`() {
        val values = listOf(90.0, 92.0, 94.0, 96.0, 98.0)
        close(90.0, FieldMetrics.percentile(values, 0.0))
        close(94.0, FieldMetrics.percentile(values, 50.0))
        close(98.0, FieldMetrics.percentile(values, 100.0))
        close(91.0, FieldMetrics.percentile(values, 12.5))
        assertNull(FieldMetrics.percentile(emptyList(), 50.0))
    }

    @Test
    fun `the VMAF scores come back sorted for the distribution`() {
        val jobs = listOf(job("a", vmaf = 97.0), job("b", vmaf = 95.5), job("c", vmaf = 99.0))
        val summary = FieldMetrics.summarise(listOf(night()), jobs) { MediaKind.VIDEO }
        assertEquals(listOf(95.5, 97.0, 99.0), summary.vmafScores)
    }

    @Test
    fun `the failure rate is over what was attempted`() {
        val summary = FieldMetrics.summarise(listOf(night(done = 90, skipped = 5, failed = 5)), emptyList()) { null }
        assertEquals(100, summary.filesAttempted)
        close(0.05, summary.failureRate)
    }

    private fun entry(state: UndoState) = UndoEntry(
        id = "u",
        mediaId = "m",
        location = UndoLocation.BIN,
        ref = MediaRef("bin://x"),
        expiresAt = null,
        state = state,
    )
}
