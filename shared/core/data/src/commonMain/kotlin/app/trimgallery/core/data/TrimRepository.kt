package app.trimgallery.core.data

import app.trimgallery.core.data.db.TrimDatabase
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.FaceEmbedding
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.Label
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.TextBlock
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import app.trimgallery.core.pipeline.TriageStep
import app.trimgallery.core.pipeline.index.IndexStep
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
) : UndoJournal,
    OriginalLocator,
    NightFacts,
    NightRun.Queue,
    NightRun.Checkpoint,
    NightRun.OnInterrupted,
    TriageStep.Sink,
    IndexStep.Sink {

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

    override suspend fun forget(entry: UndoEntry): Unit = withContext(io) {
        queries.deleteUndoEntry(entry.id)
    }

    override suspend fun expiring(nowEpochMs: Long): List<UndoEntry> = withContext(io) {
        queries.selectExpiredUndo(nowEpochMs, ::toUndoEntry).executeAsList()
    }

    override suspend fun setState(entry: UndoEntry, state: UndoState): Unit = withContext(io) {
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

    /**
     * How many "Compress now" runs have started since [sinceMs] (MONETIZATION.md: five a
     * day on Free).
     *
     * Not part of `NightFacts` — nothing about the night pass needs it, and putting it
     * there would suggest the daily count and the monthly cap are the same limit. They are
     * not: the GB cap is on background optimisation, and a Compress now job has no
     * `run_session` to be summed into it.
     */
    suspend fun compressNowsSince(sinceMs: Long): Int = withContext(io) {
        queries.compressNowsSince(sinceMs).executeAsOne().toInt()
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

    override suspend fun save(session: RunSession): Unit = withContext(io) {
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
            files_indexed = session.filesIndexed.toLong(),
            duplicates_found = session.duplicatesFound.toLong(),
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

    // ------------------------------------------------------------- folder grants

    /**
     * What the user chose for a granted folder, or null if they have never said.
     *
     * Keyed on the tree URI, because that is the identity the platform and this database
     * share. The platform decides *which* folders are granted; this row only carries what
     * to do with the originals inside one.
     */
    suspend fun folderGrant(platformRef: String): FolderGrant? = withContext(io) {
        db.trimDatabaseQueries.selectFolderGrantByRef(platformRef).executeAsOneOrNull()?.let { row ->
            FolderGrant(
                id = row.id,
                platformRef = MediaRef(row.platform_ref),
                // Not `valueOf`: an unrecognised mode — a row written by a newer build,
                // or a hand-edited database — must not throw on the way into Settings, and
                // KEEP is the reading that can never remove a file.
                mode = FolderMode.entries.firstOrNull { it.name == row.mode } ?: FolderMode.KEEP,
                displayName = row.display_name,
                offloadRef = row.offload_ref?.let(::MediaRef),
                enabled = row.enabled == 1L,
                lastScannedAt = row.last_scanned_at,
            )
        }
    }

    /**
     * Records what the user chose. Upserts on the tree URI.
     *
     * A grant revoked in system Settings leaves this row behind, deliberately: re-granting
     * the same folder restores the mode rather than silently resetting it to KEEP, which
     * is the behaviour somebody who granted, revoked and re-granted would expect.
     */
    suspend fun saveFolderGrant(grant: FolderGrant): Unit = withContext(io) {
        db.trimDatabaseQueries.upsertFolderGrant(
            id = grant.id.ifEmpty { newId() },
            platform_ref = grant.platformRef.value,
            display_name = grant.displayName,
            mode = grant.mode.name,
            offload_ref = grant.offloadRef?.value,
        )
    }

    // ------------------------------------------------------------- TriageStep.Sink

    override suspend fun stored(grants: List<FolderGrant>): List<MediaItem> = withContext(io) {
        if (grants.isEmpty()) return@withContext emptyList()
        queries.selectMediaByGrants(grants.map { it.id }, ::toMediaItem).executeAsList()
    }

    override suspend fun insert(item: MediaItem) {
        upsert(item)
    }

    override suspend fun update(item: MediaItem) {
        upsert(item)
    }

    /**
     * Deletes the row, not the file — there is nothing left to delete.
     *
     * `ON DELETE CASCADE` takes the labels, faces and jobs with it (SCHEMA.md), but
     * `undo_entry.media_id` cascades too, and an undo row is what points at an original
     * still sitting in the bin. So a file with a live undo entry keeps its row: the user
     * deleted the optimised copy, and the original they can still restore is the whole
     * reason the bin exists.
     */
    override suspend fun remove(item: MediaItem) {
        withContext(io) {
            val hasUndo = queries.selectUndoForMedia(item.id, ::toUndoEntry).executeAsList().isNotEmpty()
            if (!hasUndo) queries.deleteMedia(item.id)
        }
    }

    override suspend fun recordVerdict(
        item: MediaItem,
        status: MediaStatus,
        reason: SkipReason?,
        estSaving: Long?,
    ): Unit = withContext(io) {
        queries.setTriage(
            status = status.name,
            skipReason = reason?.name,
            estSaving = estSaving,
            now = nowMs(),
            id = item.id,
        )
    }

    @Suppress("LongMethod")
    private suspend fun upsert(item: MediaItem): Unit = withContext(io) {
        queries.upsertMedia(
            id = item.id,
            platform_ref = item.platformRef.value,
            folder_grant_id = item.folderGrantId,
            name = item.name,
            kind = item.kind.name,
            mime = item.mime,
            codec = item.codec,
            width = item.width.toLong(),
            height = item.height.toLong(),
            fps = item.fps,
            bitrate = item.bitrate,
            duration_ms = item.duration,
            size = item.size,
            mtime = item.mtime,
            taken_at = item.takenAt?.toEpochMilliseconds(),
            lat = item.location?.lat,
            lon = item.location?.lon,
            camera_model = item.cameraModel,
            flags = MediaFlagsBits.encode(item.flags),
            phash = item.phash,
            sha256 = item.sha256?.fromHex(),
            status = item.status.name,
            skip_reason = item.skipReason?.name,
            est_saving = item.estSaving,
            created_at = if (item.createdAt > 0) item.createdAt else nowMs(),
            updated_at = nowMs(),
            optimised_at = item.optimisedAt,
        )
    }

    // -------------------------------------------------------------- IndexStep.Sink

    /*
     * Every stage replaces rather than appends. A file that changed has new labels, and
     * rows from the old version would keep it in search results for content it no longer
     * contains — which reads to the user as the search being broken.
     */

    override suspend fun labels(item: MediaItem, labels: List<Label>) = withContext(io) {
        queries.transaction {
            queries.deleteLabelsFor(item.id)
            labels.forEach { queries.insertLabel(item.id, it.text, it.confidence.toDouble(), SOURCE) }
        }
    }

    override suspend fun faces(item: MediaItem, faces: List<FaceEmbedding>) = withContext(io) {
        queries.transaction {
            queries.deleteFacesFor(item.id)
            faces.forEach { face ->
                queries.insertFace(
                    id = newId(),
                    media_id = item.id,
                    person_id = null, // clustering assigns this; see FaceClustering
                    l = face.box.left.toDouble(),
                    t = face.box.top.toDouble(),
                    r = face.box.right.toDouble(),
                    b = face.box.bottom.toDouble(),
                    embedding = face.vector.toBytes(),
                    quality = null,
                )
            }
        }
    }

    override suspend fun text(item: MediaItem, blocks: List<TextBlock>) = withContext(io) {
        queries.transaction {
            queries.deleteTextFor(item.id)
            blocks.forEach { block ->
                queries.insertTextBlock(
                    media_id = item.id,
                    text = block.text,
                    l = block.box.left.toDouble(),
                    t = block.box.top.toDouble(),
                    r = block.box.right.toDouble(),
                    b = block.box.bottom.toDouble(),
                    confidence = null,
                )
            }
        }
    }

    override suspend fun hashes(item: MediaItem, phash: Long?, sha256: String?): Unit = withContext(io) {
        queries.setHashes(phash = phash, sha256 = sha256?.fromHex(), now = nowMs(), id = item.id)
    }

    override suspend fun indexed(item: MediaItem): Unit = withContext(io) {
        queries.setStatus(
            status = MediaStatus.INDEXED.name,
            skipReason = null,
            now = nowMs(),
            id = item.id,
        )
    }

    /**
     * Float embeddings as little-endian bytes.
     *
     * SCHEMA.md sizes the column for float16 and notes the estimate; this stores float32
     * for now, because halving the precision of the one number people-clustering depends on
     * is a decision to take with measurements rather than a schema comment. Recorded in
     * PROJECT.md.
     */
    private fun FloatArray.toBytes(): ByteArray {
        val out = ByteArray(size * 4)
        forEachIndexed { index, value ->
            val bits = value.toRawBits()
            out[index * 4] = (bits and 0xFF).toByte()
            out[index * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            out[index * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            out[index * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        return out
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
        optimisedAt: Long?,
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
        optimisedAt = optimisedAt,
    )

    private fun String.fromHex(): ByteArray = ByteArray(length / 2) { i ->
        ((HEX.indexOf(this[i * 2]) shl 4) or HEX.indexOf(this[i * 2 + 1])).toByte()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        val v = byte.toInt() and 0xFF
        "${HEX[v shr 4]}${HEX[v and 0xF]}"
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()

        /** SCHEMA.md `label.source`: which engine produced it. */
        const val SOURCE = "mlkit"
    }
}
