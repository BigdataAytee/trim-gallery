package app.trimgallery.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.trimgallery.core.data.db.TrimDatabase
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Space screen reads, against the real schema.
 *
 * The screen's arithmetic is `SpaceScreen` and `History` in core/domain and is tested
 * there; these are the reads that feed it, which had no tests because they had no callers.
 */
class SpaceReadsTest {

    @Test
    fun readsBackEveryNight() = runTest {
        val repository = repository()
        repository.save(session("a", startedAt = 100, bytesFreed = 5))
        repository.save(session("b", startedAt = 200, bytesFreed = 7))

        val sessions = repository.runSessions()

        assertEquals(listOf("b", "a"), sessions.map { it.id }, "newest first")
        assertEquals(12, sessions.sumOf { it.bytesFreed })
    }

    @Test
    fun keepsTheStopReasonARunEndedWith() = runTest {
        // History has to be able to say *why* a night ended; a total alone cannot
        // distinguish "finished" from "stopped".
        val repository = repository()
        repository.save(session("a", startedAt = 100, bytesFreed = 0).copy(stopReason = StopReason.THERMAL))

        assertEquals(StopReason.THERMAL, repository.runSessions().single().stopReason)
    }

    @Test
    fun readsAnUnknownStopReasonAsNothingRatherThanThrowing() = runTest {
        val driver = driver()
        val repository = repository(driver)
        repository.save(session("a", startedAt = 100, bytesFreed = 0))
        driver.execute(null, "UPDATE run_session SET stop_reason = 'ABDUCTED'", 0)

        assertNull(repository.runSessions().single().stopReason)
    }

    @Test
    fun projectsFromTheQueueAndNothingElse() = runTest {
        val repository = repository()
        repository.insert(item("a", MediaStatus.CANDIDATE, estSaving = 300))
        repository.insert(item("b", MediaStatus.CANDIDATE, estSaving = 200))
        // Not a candidate: already done, so its saving is in the total, not the projection.
        repository.insert(item("c", MediaStatus.DONE, estSaving = 900))

        assertEquals(500, repository.projectedSaving())
        assertEquals(2, repository.candidateCount())
    }

    @Test
    fun listsWhatWasLeftAlone() = runTest {
        val repository = repository()
        repository.insert(item("a", MediaStatus.SKIPPED, skipReason = SkipReason.RAW))
        repository.insert(item("b", MediaStatus.CANDIDATE))

        val skipped = repository.skipped()

        assertEquals(listOf("a"), skipped.map { it.id })
        assertEquals(SkipReason.RAW, skipped.single().skipReason)
    }

    @Test
    fun hasNoHistoryUntilSomethingRecordsAJob() = runTest {
        // Still the shape of the gap this screen ships with, but a narrower one than it was:
        // `saveJob` now exists and is tested in JobAndPredictorStoreTest. What is still
        // missing is a *caller* — `VideoOptimiseStep`, the assembly `NightRun.Step` has no
        // binding for. Until that lands, a real device records no attempts and History is
        // empty, which is what this asserts.
        assertTrue(repository().succeededJobs().isEmpty())
    }

    private fun session(id: String, startedAt: Long, bytesFreed: Long) = RunSession(
        id = id,
        startedAt = startedAt,
        finishedAt = startedAt + 1,
        bytesFreed = bytesFreed,
    )

    private fun item(id: String, status: MediaStatus, estSaving: Long? = null, skipReason: SkipReason? = null) =
        MediaItem(
            id = id,
            platformRef = MediaRef("content://$id"),
            name = "$id.mp4",
            kind = MediaKind.VIDEO,
            codec = "hevc",
            width = 1920,
            height = 1080,
            fps = 30.0,
            bitrate = 10_000_000,
            size = 100_000_000,
            duration = 60_000,
            takenAt = null,
            location = null,
            cameraModel = null,
            phash = null,
            sha256 = null,
            status = status,
            skipReason = skipReason,
            mtime = 0,
            estSaving = estSaving,
        )

    private fun driver() = testDriver()

    private fun repository(driver: JdbcSqliteDriver = driver()): TrimRepository {
        var minted = 0
        return TrimRepository(
            db = TrimDatabase(driver),
            io = Dispatchers.Unconfined,
            newId = { "id-${minted++}" },
            nowMs = { 0L },
            readSettings = { Settings() },
            readTier = { Tier.FREE },
            monthStartMs = { 0L },
        )
    }
}
