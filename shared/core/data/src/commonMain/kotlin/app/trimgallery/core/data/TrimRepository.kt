package app.trimgallery.core.data

import app.trimgallery.core.data.db.TrimDatabase
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.MediaFlags
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import app.trimgallery.core.pipeline.night.NightFacts
import app.trimgallery.core.pipeline.night.NightRun
import app.trimgallery.core.pipeline.replace.OriginalLocator
import app.trimgallery.core.pipeline.replace.UndoJournal
import app.trimgallery.engine.PauseReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * The database side of the night pass.
 *
 * One class implementing several small ports rather than one class per port: they all read
 * and write the same three tables inside the same transactions, and splitting them would
 * mean either sharing a connection through a fourth object or letting two of them disagree
 * about what the queue currently is.
 *
 * Every query is spelled with an explicit column mapper rather than the generated row
 * type. That is not a style preference — it keeps this file honest about which columns it
 * depends on, so a schema change that drops or reorders one fails to compile here instead
 * of silently mapping the wrong value.
 *
 * @param io ARCHITECTURE.md § 8 puts every database access on the IO dispatcher.
 */
class TrimRepository(
    private val db: TrimDatabase,
    private val io: CoroutineDispatcher,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val readSettings: suspend () -> Settings,
    private val readTier: suspend () -> Tier,
    /** The start of the current calendar month, for MONETIZATION.md's monthly cap. */
    private val monthStartMs: () -> Long,
) : UndoJournal, OriginalLocator, NightFacts, NightRun.Queue, NightRun.Checkpoint, NightRun.OnInterrupted {

    private val queries get() = db.trimDatabaseQueries

    // ------------------------------------------------------------ UndoJournal

    /**
     * The last step of the ARCHITECTURE.md § 7 contract.
     *
     * Nothing may report space as freed before this returns, which is why `ReplaceSequence`
     * unwinds the whole swap if it throws: an optimised file with no undo row is one the
     * user cannot get back.
     */
    override suspend fun record(entry: UndoEntry): UndoEntry = withContext(io) {
        val stored = entry.copy(id = entry.id.ifEmpty { newId() })
        queries.insertUndoEntry(
            id = stored.id,
            media_id = stored.mediaId,
            job_id = stored.jobId,
            location = stored.location.name,
            ref = stored.ref.value,
            original_size = stored.originalSize,
            expires_at = stored.expiresAt?.toEpochMilliseconds(),
            created_at = stored.createdAt?.toEpochMilliseconds() ?: 0L,
        )
        stored
    }

    override suspend fun forget(entry: UndoEntry) = withContext(io) {
        queries.deleteUndoEntry(entry.id)
    }

    override suspend fun expiring(nowEpochMs: Long): List<UndoEntry> = withContext(io) {
        queries.selectExpiredUndo(nowEpochMs, ::toUndoEntry).executeAsList()
    }

    override suspend fun setState(entry: UndoEntry, state: UndoState) = withContext(io) {
        queries.setUndoState(state = state.name, id = entry.id)
    }

    // -------------------------------------------------------- OriginalLocator

    override suspend fun refFor(mediaId: String): MediaRef? = withContext(io) {
        queries.selectMediaById(mediaId, ::toMediaItem).executeAsOneOrNull()?.platformRef
    }

    // -------------------------------------------------------------- NightFacts

    override suspend fun settings(): Settings = readSettings()

    override suspend fun tier(): Tier = readTier()

    override suspend fun largestPendingBytes(): Long = withContext(io) {
        queries.largestPendingBytes().executeAsOne()
    }

    /**
     * Summed from the sessions themselves rather than kept as a counter.
     *
     * MONETIZATION.md caps GB freed per calendar month; a counter would drift from what
     * actually happened the first time a run was killed mid-write, and a cap that is wrong
     * is either a broken promise or a lost sale.
     */
    override suspend fun bytesFreedThisMonth(): Long = withContext(io) {
        queries.bytesFreedSince(monthStartMs()).executeAsOne()
    }

    override suspend fun nextSavingBytes(): Long = withContext(io) {
        queries.nextCandidateSaving().executeAsOneOrNull() ?: 0L
    }

    // ------------------------------------------------------------ NightRun.Queue

    /**
     * The next candidate, largest estimated saving first (BUILD.md § 6).
     *
     * One row at a time, re-queried each call, rather than a list taken once at the start:
     * the pass changes what is a candidate as it goes — files finish, files are skipped —
     * and a snapshot taken an hour ago would hand back work already done.
     *
     * The row is moved to `PROCESSING` in the same breath, so a second pass starting in
     * another window cannot pick up the same file.
     */
    override suspend fun next(): MediaItem? = withContext(io) {
        var claimed: MediaItem? = null
        queries.transaction {
            val candidate = queries.selectCandidatesBySaving(limit = 1, mapper = ::toMediaItem)
                .executeAsOneOrNull() ?: return@transaction
            queries.setStatus(
                status = MediaStatus.PROCESSING.name,
                skipReason = null,
                now = nowMs(),
                id = candidate.id,
            )
            claimed = candidate.copy(status = MediaStatus.PROCESSING)
        }
        claimed
    }

    // ------------------------------------------------------- NightRun.Checkpoint

    override suspend fun save(session: RunSession) = withContext(io) {
        queries.upsertRunSession(
            id = session.id,
            started_at = session.startedAt,
            finished_at = session.finishedAt,
            stop_reason = session.stopReason?.name,
            files_done = session.filesDone.toLong(),
            files_skipped = session.filesSkipped.toLong(),
            files_failed = session.filesFailed.toLong(),
            bytes_freed = session.bytesFreed,
            minutes_worked = session.minutesWorked,
            energy_wh = session.energyWh,
            thermal_pauses = session.thermalPauses.toLong(),
            seen = if (session.seen) 1L else 0L,
        )
    }

    // ---------------------------------------------------- NightRun.OnInterrupted

    /**
     * A file the guards stopped mid-way goes back on the queue, not into the skipped list.
     *
     * ARCHITECTURE.md § 9: `any → PAUSED (guard) → same stage`. Nothing was wrong with it —
     * the phone was picked up, or it got hot — and marking it skipped would put a reason in
     * front of the user that is not true and would stop it ever being tried again.
     */
    override suspend fun interrupted(item: MediaItem, reason: PauseReason) {
        withContext(io) {
            queries.setStatus(
                status = MediaStatus.CANDIDATE.name,
                skipReason = null,
                now = nowMs(),
                id = item.id,
            )
        }
    }

    // ------------------------------------------------------------------ mapping

    @Suppress("LongParameterList")
    private fun toUndoEntry(
        id: String,
        mediaId: String,
        jobId: String?,
        location: String,
        ref: String,
        originalSize: Long?,
        expiresAt: Long?,
        state: String,
        createdAt: Long,
    ) = UndoEntry(
        id = id,
        mediaId = mediaId,
        jobId = jobId,
        location = UndoLocation.valueOf(location),
        ref = MediaRef(ref),
        originalSize = originalSize,
        expiresAt = expiresAt?.let(Instant::fromEpochMilliseconds),
        state = UndoState.valueOf(state),
        createdAt = Instant.fromEpochMilliseconds(createdAt),
    )

    @Suppress("LongParameterList")
    private fun toMediaItem(
        id: String,
        platformRef: String,
        folderGrantId: String?,
        name: String,
        kind: String,
        mime: String?,
        codec: String?,
        width: Long,
        height: Long,
        fps: Double?,
        bitrate: Long?,
        durationMs: Long?,
        size: Long,
        mtime: Long,
        takenAt: Long?,
        lat: Double?,
        lon: Double?,
        cameraModel: String?,
        flags: Long,
        phash: Long?,
        sha256: ByteArray?,
        status: String,
        skipReason: String?,
        estSaving: Long?,
        createdAt: Long,
        updatedAt: Long,
    ) = MediaItem(
        id = id,
        platformRef = MediaRef(platformRef),
        folderGrantId = folderGrantId,
        name = name,
        kind = MediaKind.valueOf(kind),
        mime = mime,
        codec = codec,
        width = width.toInt(),
        height = height.toInt(),
        fps = fps,
        bitrate = bitrate,
        size = size,
        duration = durationMs,
        takenAt = takenAt?.let(Instant::fromEpochMilliseconds),
        location = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
        cameraModel = cameraModel,
        flags = MediaFlagsBits.decode(flags),
        phash = phash,
        sha256 = sha256?.toHex(),
        status = MediaStatus.valueOf(status),
        skipReason = skipReason?.let(SkipReason::valueOf),
        mtime = mtime,
        estSaving = estSaving,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        val v = byte.toInt() and 0xFF
        "${HEX[v shr 4]}${HEX[v and 0xF]}"
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
