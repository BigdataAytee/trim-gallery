package app.trimgallery.core.domain.field

import app.trimgallery.core.model.Job
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.UndoEntry

/**
 * "Export diagnostics" (LAUNCH.md § Support, USER_JOURNEY.md § 13 under Privacy).
 *
 * **This is the only file this app ever produces that is meant to leave the device.** The
 * app has no `INTERNET` permission and a build guard enforces it (ARCHITECTURE.md § 14), so
 * nothing here can send anything — the user exports the file and shares it themselves,
 * deliberately, from the system sheet. That makes the whole design question a single one:
 * *what is a user agreeing to when they tap this?*
 *
 * The answer has to be small enough to read. So the contents are built as an explicit list
 * of permitted fields ([FileRow]) rather than by serialising the rows the app already has.
 * That is the structural difference between a redaction that holds and one that lasts until
 * someone adds a column: a new field on `MediaItem` does not appear here unless somebody
 * writes it in, and a test asserts that nothing identifying leaks even when every string in
 * the library is a distinctive one.
 *
 * ### What is never in it
 *
 * - **Filenames, paths and URIs.** A SAF document URI carries the folder and usually the
 *   filename, which between them can name a person, a place or an employer.
 * - **Location.** Obviously, and including the coarse version too — a rounded coordinate
 *   still says which country somebody's holiday was in.
 * - **Timestamps of anything but this export.** When a photograph was taken says where
 *   somebody was; when the night pass ran says when they sleep. Durations and elapsed times
 *   carry every fact the field test needs and none of these.
 * - **Content hashes, perceptual or exact.** A SHA-256 identifies a file to anyone who
 *   already has it, and a perceptual hash matches a picture against a set of known ones.
 *   Neither tells us anything about compression.
 * - **Anything the index produced** — labels, text found in photographs, faces, people,
 *   album and folder names. The index is the most private thing the app holds.
 * - **Row ids.** UUIDv7 embeds the millisecond it was minted (SCHEMA.md), so a list of ids
 *   is a timeline. Files are numbered from one, per export.
 *
 * ### What is in it, and why each thing earns its place
 *
 * BUILD.md § 14's list, and nothing beyond it: source codec, bitrate, resolution, frame rate
 * and camera *model* — the model, which is a product name shared by millions, not a body
 * serial — then what the search and the encoder did with them. Every one of those is needed
 * to answer the question the field test asks, which is why a given phone got the saving it
 * got.
 */
object Diagnostics {

    /** The phone, as a product rather than as a person's phone. */
    data class Device(
        val model: String,
        val osVersion: String,
        val appVersion: String,
        /** From `EnergyEstimate`'s families; a rough class, not a chip serial. */
        val chipFamily: String? = null,
    )

    /**
     * One file's numbers, and the complete list of what may be said about it.
     *
     * Deliberately not a projection of `MediaItem` or `Job`: it is written out field by
     * field so that adding a column to either of those cannot widen what is exported.
     */
    data class FileRow(
        /** Position in this export, from 1. Not a row id — those carry a timestamp. */
        val index: Int,
        val kind: MediaKind,
        val sourceCodec: String?,
        val sourceBitrate: Long?,
        val width: Int,
        val height: Int,
        val fps: Double?,
        val cameraModel: String?,
        val engine: String?,
        val setting: String?,
        val probes: Int,
        val attempts: Int,
        val xpsnr: Double?,
        val vmaf: Double?,
        val ssim2: Double?,
        val factor: Double?,
        val encodeMs: Long?,
        val verifyMs: Long?,
        val realtimeMultiple: Double?,
        val thermalStart: Float?,
        val thermalEnd: Float?,
        val energyWh: Double?,
        val state: JobState,
        /** The class of error, never its message: a message can quote a path. */
        val failed: Boolean,
    )

    /** The lines of the header, which say what was left out as well as what was put in. */
    val EXCLUDED = listOf(
        "file names, paths and folder names",
        "locations",
        "dates and times, other than the date of this export",
        "content hashes",
        "labels, text found in photos, faces and people",
        "anything that identifies a file or a person",
    )

    /**
     * Builds the whole report.
     *
     * @param itemOf the source item for a job, for the fields BUILD.md § 14 wants about the
     *   input. Only the permitted ones are read.
     * @param generatedOn the export's own date, as the user's locale renders it — the one
     *   timestamp in the file, and there so that whoever reads it knows how old it is.
     */
    fun build(
        device: Device,
        sessions: List<RunSession>,
        jobs: List<Job>,
        undo: List<UndoEntry> = emptyList(),
        generatedOn: String,
        itemOf: (Job) -> MediaItem?,
    ): String {
        val summary = FieldMetrics.summarise(sessions, jobs, undo) { itemOf(it)?.kind }
        val rows = rows(jobs, itemOf)

        return buildString {
            appendLine("Trim Gallery diagnostics")
            appendLine("Generated $generatedOn")
            appendLine()
            appendLine("This file contains measurements only. It does not contain:")
            EXCLUDED.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("Device")
            appendLine("  model: ${device.model}")
            appendLine("  os: ${device.osVersion}")
            appendLine("  app: ${device.appVersion}")
            device.chipFamily?.let { appendLine("  chip class: $it") }
            appendLine()
            appendLine("Totals over ${summary.nights} night(s)")
            appendLine("  files optimised: ${summary.filesDone}")
            appendLine("  files skipped: ${summary.filesSkipped}")
            appendLine("  files failed: ${summary.filesFailed}")
            appendLine("  files indexed: ${summary.filesIndexed}")
            appendLine("  duplicate groups found: ${summary.duplicatesFound}")
            appendLine("  bytes freed: ${summary.bytesFreed}")
            appendLine("  minutes worked: ${summary.minutesWorked}")
            appendLine("  energy (Wh): ${summary.energyWh}")
            appendLine("  GB per hour: ${summary.gbPerHour ?: "not measured"}")
            appendLine("  Wh per GB: ${summary.whPerGb ?: "not measured"}")
            appendLine("  median video saving: ${summary.medianVideoSaving ?: "not measured"}")
            appendLine("  median photo saving: ${summary.medianPhotoSaving ?: "not measured"}")
            appendLine("  thermal pauses per night: ${summary.thermalPausesPerNight ?: "not measured"}")
            appendLine("  restore rate: ${summary.restoreRate ?: "not measured"}")
            appendLine()
            appendLine("Per file (${rows.size} row(s))")
            appendLine(HEADER)
            rows.forEach { appendLine(csv(it)) }
        }
    }

    /** The redacted rows on their own, for a caller that wants them without the prose. */
    fun rows(jobs: List<Job>, itemOf: (Job) -> MediaItem?): List<FileRow> =
        jobs.mapIndexedNotNull { position, job ->
            val item = itemOf(job) ?: return@mapIndexedNotNull null
            FileRow(
                index = position + 1,
                kind = item.kind,
                sourceCodec = item.codec,
                sourceBitrate = item.bitrate,
                width = item.width,
                height = item.height,
                fps = item.fps,
                cameraModel = item.cameraModel,
                engine = job.engine,
                setting = job.setting,
                probes = job.probes,
                attempts = job.attempts,
                xpsnr = job.xpsnr,
                vmaf = job.vmaf,
                ssim2 = job.ssim2,
                factor = job.factor,
                encodeMs = job.encodeMs,
                verifyMs = job.verifyMs,
                realtimeMultiple = job.realtimeMultiple,
                thermalStart = job.thermalStart,
                thermalEnd = job.thermalEnd,
                energyWh = job.energyWh,
                state = job.state,
                // The flag, never `job.error`: an exception message can quote a path, and a
                // path is a filename with its folder attached.
                failed = job.state == JobState.FAILED,
            )
        }

    const val HEADER =
        "index,kind,source_codec,source_bitrate,width,height,fps,camera_model,engine,setting," +
            "probes,attempts,xpsnr,vmaf,ssim2,factor,encode_ms,verify_ms,realtime_multiple," +
            "thermal_start,thermal_end,energy_wh,state,failed"

    private fun csv(row: FileRow): String = listOf(
        row.index.toString(),
        row.kind.name,
        field(row.sourceCodec),
        row.sourceBitrate?.toString().orEmpty(),
        row.width.toString(),
        row.height.toString(),
        row.fps?.toString().orEmpty(),
        field(row.cameraModel),
        field(row.engine),
        field(row.setting),
        row.probes.toString(),
        row.attempts.toString(),
        row.xpsnr?.toString().orEmpty(),
        row.vmaf?.toString().orEmpty(),
        row.ssim2?.toString().orEmpty(),
        row.factor?.toString().orEmpty(),
        row.encodeMs?.toString().orEmpty(),
        row.verifyMs?.toString().orEmpty(),
        row.realtimeMultiple?.toString().orEmpty(),
        row.thermalStart?.toString().orEmpty(),
        row.thermalEnd?.toString().orEmpty(),
        row.energyWh?.toString().orEmpty(),
        row.state.name,
        row.failed.toString(),
    ).joinToString(",")

    /**
     * A free-text field, made safe for a comma-separated line.
     *
     * Commas, quotes and newlines are stripped rather than escaped. The only free text that
     * reaches here is a codec name, a camera model and an encoder setting, none of which
     * legitimately contains any of the three — so anything that does is either a mangled
     * value or something that should not be in this file, and dropping the characters that
     * would let it break out of its column is the right answer to both.
     */
    private fun field(value: String?): String =
        value?.filterNot { it == ',' || it == '"' || it == '\n' || it == '\r' }.orEmpty()
}
