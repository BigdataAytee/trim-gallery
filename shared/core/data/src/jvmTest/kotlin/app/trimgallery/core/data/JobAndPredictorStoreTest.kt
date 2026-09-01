package app.trimgallery.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.trimgallery.core.data.db.TrimDatabase
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.Job
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.Settings
import app.trimgallery.core.pipeline.Predictor
import app.trimgallery.engine.VideoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The two tables the night pass writes and nothing had ever written to.
 *
 * `job` records what an attempt did — it is what the History screen lists and what the
 * field test measures — and `predictor` is what makes the second night on a phone cheaper
 * than the first. Both had a schema, both had queries, and neither had a caller, because
 * the step that would call them does not exist yet.
 *
 * These tests are the half that can be proved without it.
 */
class JobAndPredictorStoreTest {

    @Test
    fun recordsWhatAnAttemptDid() = runTest {
        val repository = repository()
        repository.insert(item())
        repository.saveJob(job())

        val stored = repository.jobsFor(MEDIA).single()

        assertEquals(JobState.SUCCEEDED, stored.state)
        assertEquals(100_000_000, stored.originalSize)
        assertEquals(40_000_000, stored.newSize)
        assertEquals(96.4, stored.vmaf)
        assertEquals(60_000_000, stored.saved, "what the History screen shows")
    }

    @Test
    fun keepsTheNumbersTheFieldTestIsScoredOn() = runTest {
        // BUILD.md § 14: the factor, the realtime multiple, the thermal readings and the
        // energy are the per-file metrics, and a round trip that quietly dropped one would
        // be found only when the field test came back empty.
        val repository = repository()
        repository.insert(item())
        repository.saveJob(
            job().copy(
                xpsnr = 44.5,
                probes = 3,
                realtimeMultiple = 4.2,
                thermalStart = 0.3f,
                thermalEnd = 0.55f,
                energyWh = 12.5,
                encodeMs = 90_000,
                verifyMs = 4_000,
                engine = "MediaCodec",
                setting = "6000000bps VBR",
            ),
        )

        val stored = repository.jobsFor(MEDIA).single()

        assertEquals(44.5, stored.xpsnr)
        assertEquals(3, stored.probes)
        assertEquals(4.2, stored.realtimeMultiple)
        assertEquals(0.3f, stored.thermalStart)
        assertEquals(0.55f, stored.thermalEnd)
        assertEquals(12.5, stored.energyWh)
        assertEquals(90_000, stored.encodeMs)
        assertEquals(4_000, stored.verifyMs)
        assertEquals("MediaCodec", stored.engine)
        assertEquals("6000000bps VBR", stored.setting)
    }

    @Test
    fun overwritesTheAttemptRatherThanAddingASecondOne() = runTest {
        // The row is written when the attempt starts and again as it progresses, so a
        // night killed mid-file leaves the last state it reached. Two rows for one attempt
        // would double-count the file in every total on the Space screen.
        val repository = repository()
        repository.insert(item())
        repository.saveJob(job().copy(state = JobState.ENCODING, newSize = null))
        repository.saveJob(job().copy(state = JobState.SUCCEEDED, newSize = 40_000_000))

        val stored = repository.jobsFor(MEDIA)

        assertEquals(1, stored.size)
        assertEquals(JobState.SUCCEEDED, stored.single().state)
    }

    @Test
    fun aSucceededJobIsWhatHistoryLists() = runTest {
        val repository = repository()
        repository.insert(item())
        repository.saveJob(job())
        repository.saveJob(job().copy(id = "job-2", state = JobState.FAILED))

        // Only the one that changed something. A failed attempt belongs on the Skipped
        // screen with its reason, not in a history of changes that were made.
        assertEquals(listOf("job-1"), repository.succeededJobs().map { it.id })
    }

    @Test
    fun readsAnUnknownJobStateAsFailed() = runTest {
        val driver = driver()
        val repository = repository(driver)
        repository.insert(item())
        repository.saveJob(job())
        driver.execute(null, "UPDATE job SET state = 'TELEPORTING'", 0)

        // Not an exception, and not SUCCEEDED: a state this build cannot read is not a
        // state it may treat as a completed replacement.
        assertEquals(JobState.FAILED, repository.jobsFor(MEDIA).single().state)
    }

    @Test
    fun knowsNothingAboutAFamilyItHasNotMet() = runTest {
        assertNull(repository().prediction(key()))
    }

    @Test
    fun remembersWhatItLearnedAboutAFamily() = runTest {
        val repository = repository()
        repository.learn(Predictor.learn(existing = null, key = key(), winningBps = 6_000_000))

        val entry = repository.prediction(key())

        assertEquals(6_000_000, entry?.settingBps)
        assertEquals(1, entry?.samples)
    }

    @Test
    fun foldsASecondResultIntoTheSameFamily() = runTest {
        // The whole point of the table: the second night on a phone is cheaper than the
        // first because the search starts from what the first one learned.
        val repository = repository()
        repository.learn(Predictor.learn(null, key(), 6_000_000))
        repository.learn(Predictor.learn(repository.prediction(key()), key(), 8_000_000))

        val entry = repository.prediction(key())

        assertEquals(2, entry?.samples)
        assertEquals(7_000_000, entry?.settingBps, "the running mean of the two")
    }

    @Test
    fun keepsFamiliesWithDifferentOutputCodecsApart() = runTest {
        // AV1 reaches the same quality at roughly two thirds of HEVC's bitrate, so a table
        // keyed without the output codec would average the two and predict a number wrong
        // for both.
        val repository = repository()
        repository.learn(Predictor.learn(null, key(VideoCodec.HEVC), 6_000_000))
        repository.learn(Predictor.learn(null, key(VideoCodec.AV1), 4_000_000))

        assertEquals(6_000_000, repository.prediction(key(VideoCodec.HEVC))?.settingBps)
        assertEquals(4_000_000, repository.prediction(key(VideoCodec.AV1))?.settingBps)
    }

    private fun key(output: VideoCodec = VideoCodec.HEVC) = Predictor.Key(
        platform = "android",
        device = "Pixel 8",
        cameraModel = "Pixel 8 back camera",
        codec = "hevc",
        outputCodec = output,
        width = 1920,
        height = 1080,
        fps = 30,
        bitrateBucket = 10,
    )

    private fun job() = Job(
        id = "job-1",
        mediaId = MEDIA,
        state = JobState.SUCCEEDED,
        originalSize = 100_000_000,
        newSize = 40_000_000,
        vmaf = 96.4,
        startedAt = Instant.fromEpochMilliseconds(1_000),
        finishedAt = Instant.fromEpochMilliseconds(2_000),
    )

    private fun item() = MediaItem(
        id = MEDIA,
        platformRef = MediaRef("content://$MEDIA"),
        name = "holiday.mp4",
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
        status = MediaStatus.CANDIDATE,
        mtime = 0,
    )

    private fun driver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TrimDatabase.Schema.create(it) }

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

    private companion object {
        const val MEDIA = "media-1"
    }
}
