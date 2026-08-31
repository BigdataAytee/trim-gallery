package app.trimgallery.core.domain.field

import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.Job
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.RunSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DiagnosticsTest {

    /**
     * Every string on this item is a sentinel a real library could plausibly contain, so a
     * field exported carelessly shows up as a distinctive token in the output rather than as
     * something that reads like part of the report.
     */
    private val secret = MediaItem(
        id = "0192f3a4-SECRET-ROW-ID",
        platformRef = MediaRef("content://com.android.externalstorage/tree/primary%3ADCIM%2FSECRET-FOLDER"),
        name = "SECRET-FILENAME-mum-hospital.mp4",
        kind = MediaKind.VIDEO,
        codec = "hevc",
        width = 3840,
        height = 2160,
        fps = 30.0,
        bitrate = 42_000_000,
        size = 400_000_000,
        duration = 120_000,
        takenAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        location = GeoPoint(lat = 51.5074, lon = -0.1278),
        cameraModel = "Pixel 9 Pro",
        phash = 0x5EC8E7L,
        sha256 = "SECRETHASHf00dcafe",
        mtime = 1_700_000_000_000,
        folderGrantId = "SECRET-GRANT",
    )

    private val job = Job(
        id = "0192f3a4-SECRET-JOB-ID",
        mediaId = secret.id,
        state = JobState.SUCCEEDED,
        engine = "hevc",
        setting = "vbr 18000000",
        probes = 2,
        attempts = 1,
        xpsnr = 41.2,
        vmaf = 96.4,
        originalSize = 400_000_000,
        newSize = 220_000_000,
        encodeMs = 31_000,
        verifyMs = 4_100,
        realtimeMultiple = 3.9,
        thermalStart = 0.3f,
        thermalEnd = 0.55f,
        energyWh = 1.9,
        error = "open failed: /storage/emulated/0/DCIM/SECRET-FILENAME-mum-hospital.mp4",
    )

    private val device = Diagnostics.Device(
        model = "Pixel 9 Pro",
        osVersion = "Android 16",
        appVersion = "1.0.0",
        chipFamily = "flagship",
    )

    private fun report(jobs: List<Job> = listOf(job)) = Diagnostics.build(
        device = device,
        sessions = listOf(
            RunSession(
                id = "0192f3a4-SECRET-SESSION",
                startedAt = 1_700_000_000_000,
                finishedAt = 1_700_003_600_000,
                filesDone = 12,
                filesSkipped = 3,
                filesFailed = 1,
                bytesFreed = 3L * 1024 * 1024 * 1024,
                minutesWorked = 45.0,
                energyWh = 12.0,
                thermalPauses = 2,
                filesIndexed = 900,
                duplicatesFound = 14,
            ),
        ),
        jobs = jobs,
        generatedOn = "30 August 2026",
        itemOf = { secret },
    )

    // ------------------------------------------------------------- redaction

    /**
     * The test the whole class is written for. Every one of these is a real category of
     * thing a careless field would export, and a SAF URI alone carries the folder and
     * usually the filename — which between them can name a person, a place or an employer.
     */
    @Test
    fun `nothing identifying reaches the file`() {
        val text = report()
        for (secretToken in listOf(
            "SECRET-FILENAME",
            "SECRET-FOLDER",
            "SECRET-ROW-ID",
            "SECRET-JOB-ID",
            "SECRET-SESSION",
            "SECRET-GRANT",
            "SECRETHASH",
            "content://",
            "mum-hospital",
            "51.5074",
            "-0.1278",
            "/storage/",
        )) {
            assertFalse(text.contains(secretToken), "leaked \"$secretToken\" into:\n$text")
        }
    }

    /** When a photograph was taken says where somebody was; when a night ran says when they sleep. */
    @Test
    fun `no absolute timestamps but the export's own date`() {
        val text = report()
        assertFalse(text.contains("1700000000000"), text)
        assertFalse(text.contains("1_700_000_000_000"), text)
        assertTrue(text.contains("30 August 2026"))
    }

    /** An exception message can quote a path, and a path is a filename with its folder on it. */
    @Test
    fun `error messages are reduced to a flag`() {
        val failed = job.copy(state = JobState.FAILED)
        val text = report(listOf(failed))
        assertFalse(text.contains("open failed"), text)
        assertTrue(text.contains("FAILED"))
        assertTrue(Diagnostics.rows(listOf(failed)) { secret }.single().failed)
    }

    @Test
    fun `a perceptual hash is a fingerprint and is not exported`() {
        val rows = Diagnostics.rows(listOf(job)) { secret }
        val text = report()
        assertFalse(text.contains(secret.phash.toString()))
        assertEquals(1, rows.single().index, "files are numbered from one, not identified")
    }

    // ---------------------------------------------------------- what is in it

    /** BUILD.md § 14's per-file list, which is the whole reason the file exists. */
    @Test
    fun `every per-file metric BUILD asks for is present`() {
        val row = Diagnostics.rows(listOf(job)) { secret }.single()
        assertEquals("hevc", row.sourceCodec)
        assertEquals(42_000_000, row.sourceBitrate)
        assertEquals("Pixel 9 Pro", row.cameraModel)
        assertEquals("vbr 18000000", row.setting)
        assertEquals(2, row.probes)
        assertEquals(41.2, row.xpsnr)
        assertEquals(96.4, row.vmaf)
        assertEquals(0.55, row.factor)
        assertEquals(31_000, row.encodeMs)
        assertEquals(3.9, row.realtimeMultiple)
        assertEquals(0.3f, row.thermalStart)
        assertEquals(0.55f, row.thermalEnd)
    }

    /** A camera model is a product name shared by millions; a body serial is not, and is absent. */
    @Test
    fun `the camera model is a product, and it is kept`() {
        assertTrue(report().contains("Pixel 9 Pro"))
    }

    @Test
    fun `the per-night totals BUILD asks for are present`() {
        val text = report()
        for (line in listOf("files indexed: 900", "duplicate groups found: 14", "GB per hour", "Wh per GB")) {
            assertTrue(text.contains(line), "missing \"$line\" in:\n$text")
        }
    }

    // -------------------------------------------------------------- the header

    /**
     * What makes "opt-in" mean anything: a user can read what they are about to share, and
     * the file says out loud what it left out.
     */
    @Test
    fun `the file says what it does not contain`() {
        val text = report()
        assertTrue(text.contains("It does not contain"))
        for (excluded in Diagnostics.EXCLUDED) {
            assertTrue(text.contains(excluded), "header did not mention \"$excluded\"")
        }
    }

    // ------------------------------------------------------------------- shape

    @Test
    fun `the rows line up with the header`() {
        val text = report().lines()
        val header = text.single { it == Diagnostics.HEADER }
        val row = text[text.indexOf(header) + 1]
        assertEquals(header.count { it == ',' }, row.count { it == ',' }, "row does not match header:\n$row")
    }

    /**
     * The only free text here is a codec, a camera model and an encoder setting, none of
     * which legitimately contains a comma — so anything that does is either mangled or
     * something that should not be in this file, and either way it must not break out of its
     * column.
     */
    @Test
    fun `a comma in a field cannot break the row apart`() {
        val awkward = job.copy(setting = "vbr,18000000\nrogue")
        val text = Diagnostics.build(device, emptyList(), listOf(awkward), generatedOn = "today") { secret }
        val lines = text.lines()
        val header = lines.single { it == Diagnostics.HEADER }
        val row = lines[lines.indexOf(header) + 1]
        assertEquals(header.count { it == ',' }, row.count { it == ',' }, row)
        assertFalse(row.contains("\n"))
    }

    @Test
    fun `a job whose item is gone is left out rather than exported blank`() {
        val text = Diagnostics.build(device, emptyList(), listOf(job), generatedOn = "today") { null }
        assertTrue(text.contains("Per file (0 row(s))"), text)
    }

    @Test
    fun `an empty export is still a readable file`() {
        val text = Diagnostics.build(device, emptyList(), emptyList(), generatedOn = "today") { null }
        assertTrue(text.contains("Trim Gallery diagnostics"))
        assertTrue(text.contains("not measured"))
    }
}
