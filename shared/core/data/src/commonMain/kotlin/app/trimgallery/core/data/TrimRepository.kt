package app.trimgallery.core.data

import app.trimgallery.core.data.db.TrimDatabase
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.Job
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.core.model.StopReason
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import app.trimgallery.core.pipeline.LibraryDiff
import app.trimgallery.core.pipeline.Predictor
import app.trimgallery.core.pipeline.TriageStep
import app.trimgallery.core.pipeline.night.NightFacts
import app.trimgallery.core.pipeline.night.NightRun
import app.trimgallery.core.pipeline.replace.OriginalLocator
import app.trimgallery.core.pipeline.replace.UndoJournal
import app.trimgallery.engine.PauseReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
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
    TriageStep.Sink {

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

    // --------------------------------------------------------------- the library

    /**
     * Everything the grid shows, newest first, straight from the database.
     *
     * This is what makes a start fast: the grid is drawn from rows written the last time the
     * app ran, before any folder is walked. The walk still happens — in the background — and
     * its result is diffed in, but the user is looking at their photographs while that goes
     * on rather than at a spinner.
     *
     * Sorted in SQL rather than in Kotlin. The host used to re-sort the whole list every
     * time the scan published a batch, which is O(n log n) per batch on a list that only
     * grows.
     */
    suspend fun gallery(): List<MediaItem> = withContext(io) {
        queries.selectGallery(::toMediaItem).executeAsList()
    }

    /**
     * Writes one scan's worth of changes, in one transaction.
     *
     * Removals go through the same rule [remove] applies: a row with a live undo entry keeps
     * its place, because that row is the only handle on an original still sitting in the
     * bin. Deleting it would lose a file the user can still restore.
     *
     * `unchanged` is not written at all. It is most of the library on most nights, and
     * rewriting a row to the value it already holds is the largest avoidable cost in the
     * whole scan.
     */
    suspend fun applyScan(diff: LibraryDiff.Result): Unit = withContext(io) {
        val now = nowMs()
        queries.transaction {
            diff.added.forEach(::writeMedia)
            diff.modified.forEach { change -> writeMedia(LibraryDiff.merge(change.stored, change.scanned, now)) }
            diff.removed.forEach { item ->
                val hasUndo = queries.selectUndoForMedia(item.id, ::toUndoEntry).executeAsList().isNotEmpty()
                if (!hasUndo) queries.deleteMedia(item.id)
            }
        }
    }

    // ------------------------------------------------------------------- the job

    /**
     * Records an attempt on one file, creating the row or overwriting it in place.
     *
     * Written when the attempt starts and again as it progresses, so a night killed
     * mid-file leaves the last state it reached rather than nothing — the same reason
     * `save(session)` replaces rather than updates.
     *
     * Nothing called this for the first nine milestones because nothing ran an attempt:
     * `NightRun.Step` has no binding, so `job` was a table with a schema, a foreign key and
     * no writer. Recorded in PROJECT.md.
     */
    suspend fun saveJob(job: Job): Unit = withContext(io) {
        queries.upsertJob(
            id = job.id.ifEmpty { newId() },
            media_id = job.mediaId,
            run_session_id = job.runSessionId,
            state = job.state.name,
            stage_before_pause = job.stageBeforePause?.name,
            engine = job.engine,
            setting = job.setting,
            probes = job.probes.toLong(),
            xpsnr = job.xpsnr,
            vmaf = job.vmaf,
            ssim2 = job.ssim2,
            original_size = job.originalSize,
            new_size = job.newSize,
            encode_ms = job.encodeMs,
            verify_ms = job.verifyMs,
            realtime_multiple = job.realtimeMultiple,
            thermal_start = job.thermalStart?.toDouble(),
            thermal_end = job.thermalEnd?.toDouble(),
            energy_wh = job.energyWh,
            attempts = job.attempts.toLong(),
            error = job.error,
            user_initiated = if (job.userInitiated) 1L else 0L,
            started_at = job.startedAt?.toEpochMilliseconds(),
            finished_at = job.finishedAt?.toEpochMilliseconds(),
        )
    }

    /**
     * Records the folders the platform says are granted, without touching what the user
     * chose for them.
     *
     * **This is what stops the app crashing on the first scan.** `media_item.folder_grant_id`
     * references `folder_grant(id)`, and `AndroidDatabase` turns foreign keys on because
     * SCHEMA.md's cascades are inert without them. Nothing wrote a `folder_grant` row when a
     * folder was granted — `saveFolderGrant` only runs when somebody picks a mode in
     * Settings — so the first scan inserted media rows pointing at a table with nothing in
     * it, and SQLite refused. Every launch afterwards read the persisted grant and did it
     * again, which is the crash loop.
     *
     * Insert-if-missing only, deliberately: calling this on every launch must not reset a
     * folder the user set to Offload back to Keep. [saveFolderGrant] is the one that writes
     * a choice.
     *
     * Called with what the platform reports, so it also repairs a device already stuck in
     * the loop: the grant is still persisted, and the row it was missing appears before the
     * scan that needs it.
     */
    suspend fun recordGrants(grants: List<FolderGrant>): Unit = withContext(io) {
        queries.transaction {
            grants.forEach { grant ->
                queries.insertFolderGrantIfMissing(
                    // The tree URI, as both id and platform_ref. `GrantedFolders` argues
                    // that the tree URI *is* the stable identity of a grant and that minting
                    // a UUID would create a second identity to keep in step with the first —
                    // and then `saveFolderGrant` minted exactly that. Two identities for one
                    // folder is what let the scan reference a row that could never match.
                    id = grant.id.ifEmpty { grant.platformRef.value },
                    platform_ref = grant.platformRef.value,
                    display_name = grant.displayName,
                    mode = grant.mode.name,
                    offload_ref = grant.offloadRef?.value,
                )
            }
        }
    }

    /** Every attempt on one file, newest first. */
    suspend fun jobsFor(mediaId: String): List<Job> = withContext(io) {
        queries.selectJobsForMedia(mediaId, ::toFullJob).executeAsList()
    }

    // ------------------------------------------------------------- the predictor

    /**
     * What this device has learned about a family of files, or null if it has met none.
     *
     * The table has been in SCHEMA.md since milestone 3 with both its queries written and
     * neither called, for the same reason `job` had no writer: the search that would consult
     * it and the encode that would teach it both hang off a night pass with no step.
     */
    suspend fun prediction(key: Predictor.Key): Predictor.Entry? = withContext(io) {
        queries.selectPredictor(
            platform = key.platform,
            device = key.device,
            camera_model = key.cameraModel,
            codec = key.codec,
            output_codec = key.outputCodec.name,
            width = key.width.toLong(),
            height = key.height.toLong(),
            fps_bucket = key.fps.toLong(),
            bitrate_bucket = key.bitrateBucket.toLong(),
        ) { _, _, _, _, _, _, _, _, _, settingMean, settingVar, samples, _ ->
            Predictor.Entry(
                key = key,
                // The mean is stored as a REAL because `Predictor.learn` keeps a running
                // average, and a bitrate is a whole number of bits per second on the way
                // back out.
                settingBps = settingMean.roundToInt(),
                samples = samples.toInt(),
                settingVar = settingVar,
            )
        }.executeAsOneOrNull()
    }

    /** Folds one result into the family's running mean and variance. */
    suspend fun learn(entry: Predictor.Entry): Unit = withContext(io) {
        queries.upsertPredictor(
            platform = entry.key.platform,
            device = entry.key.device,
            camera_model = entry.key.cameraModel,
            codec = entry.key.codec,
            output_codec = entry.key.outputCodec.name,
            width = entry.key.width.toLong(),
            height = entry.key.height.toLong(),
            fps_bucket = entry.key.fps.toLong(),
            bitrate_bucket = entry.key.bitrateBucket.toLong(),
            setting_mean = entry.settingBps.toDouble(),
            setting_var = entry.settingVar,
            samples = entry.samples.toLong(),
            updated_at = nowMs(),
        )
    }

    // --------------------------------------------------------------- Space screen

    /**
     * Every recorded night, newest first.
     *
     * `SpaceScreen.state` sums these itself rather than being handed totals, so that the
     * screen's arithmetic is the tested arithmetic and this method stays a plain read.
     */
    suspend fun runSessions(): List<RunSession> = withContext(io) {
        queries.selectRunSessions(::toRunSession).executeAsList()
    }

    /** The night in progress, or null. A row with no finish time is one still running. */
    suspend fun currentRunSession(): RunSession? = withContext(io) {
        queries.lastRunSession(::toRunSession).executeAsOneOrNull()?.takeIf { it.finishedAt == null }
    }

    /** How many files are still waiting, which is the progress ring's denominator. */
    suspend fun candidateCount(): Int = withContext(io) {
        queries.countCandidates().executeAsOne().toInt()
    }

    /** What the whole queue is expected to give back. A projection, and labelled as one. */
    suspend fun projectedSaving(): Long = withContext(io) {
        queries.sumCandidateSaving().executeAsOne()
    }

    /**
     * The changes History lists, newest first.
     *
     * Job rows are written now: `OptimiseController` records one before each encode it
     * starts, and `VideoOptimiseStep` is bound into the night pass. This returned an empty
     * list on every device for four milestones while both of those were missing, and the
     * comment saying so outlived the fix — which is its own small lesson about comments
     * that describe an absence.
     */
    suspend fun succeededJobs(limit: Long = HISTORY_LIMIT): List<Job> = withContext(io) {
        queries.selectSucceededJobs(limit, ::toJob).executeAsList()
    }

    /** Every undo row, whatever its state, keyed by the media it belongs to. */
    suspend fun undoByMedia(): Map<String, UndoEntry> = withContext(io) {
        queries.selectAllUndo(::toUndoEntry).executeAsList().associateBy { it.mediaId }
    }

    /**
     * What triage decided is worth optimising, biggest saving first.
     *
     * Sorted here rather than in SQL because the ordering is a property of the screen, not
     * of the table: Big files leads with the largest saving because that is the row most
     * worth a tap, and a different screen could reasonably want a different order. The
     * estimate is the Triager's own projection — the same number the night pass acts on,
     * not a second guess computed for display.
     */
    suspend fun candidates(): List<MediaItem> = withContext(io) {
        queries.selectByStatus(MediaStatus.CANDIDATE.name, ::toMediaItem)
            .executeAsList()
            .sortedByDescending { it.estSaving ?: 0L }
    }

    /** Everything triage declined to touch, for the Skipped list. */
    suspend fun skipped(): List<MediaItem> = withContext(io) {
        queries.selectByStatus(MediaStatus.SKIPPED.name, ::toMediaItem).executeAsList()
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
        queries.selectFolderGrantByRef(platformRef, ::toFolderGrant).executeAsOneOrNull()
    }

    /**
     * Records what the user chose. Upserts on the tree URI.
     *
     * A grant revoked in system Settings leaves this row behind, deliberately: re-granting
     * the same folder restores the mode rather than silently resetting it to KEEP, which
     * is the behaviour somebody who granted, revoked and re-granted would expect.
     */
    suspend fun saveFolderGrant(grant: FolderGrant): Unit = withContext(io) {
        queries.transaction {
            // Insert-or-ignore then update, in that order: the insert is a no-op when the
            // folder already has a row, and the update then carries the choice in either
            // case. An existing row keeps the id it already has, and keeps whatever
            // `last_scanned_at` the scanner has written.
            queries.insertFolderGrantIfMissing(
                id = grant.id.ifEmpty { grant.platformRef.value },
                platform_ref = grant.platformRef.value,
                display_name = grant.displayName,
                mode = grant.mode.name,
                offload_ref = grant.offloadRef?.value,
            )
            queries.updateFolderGrant(
                display_name = grant.displayName,
                mode = grant.mode.name,
                offload_ref = grant.offloadRef?.value,
                platform_ref = grant.platformRef.value,
            )
        }
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
    private suspend fun upsert(item: MediaItem): Unit = withContext(io) { writeMedia(item) }

    /**
     * The write itself, outside the dispatcher switch so a batch can share one transaction.
     *
     * A first scan of a fifty-thousand-item library is fifty thousand of these. One
     * transaction each is fifty thousand commits, and SQLite fsyncs on every one; inside a
     * single transaction it is one. That difference is most of why the grid used to take so
     * long to arrive.
     */
    private fun writeMedia(item: MediaItem) {
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

    // ------------------------------------------------------------------ mapping

    /**
     * Every column on `job`, for the callers that want the whole attempt.
     *
     * Separate from `toJob`, which maps the five columns `selectSucceededJobs` asks for.
     * Two mappers rather than one over `SELECT *` because the narrow query is the one the
     * Space screen runs over hundreds of rows, and widening it to share a mapper would read
     * nineteen columns per row to display four.
     */
    @Suppress("LongParameterList")
    private fun toFullJob(
        id: String,
        mediaId: String,
        runSessionId: String?,
        state: String,
        stageBeforePause: String?,
        engine: String?,
        setting: String?,
        probes: Long,
        xpsnr: Double?,
        vmaf: Double?,
        ssim2: Double?,
        originalSize: Long?,
        newSize: Long?,
        encodeMs: Long?,
        verifyMs: Long?,
        realtimeMultiple: Double?,
        thermalStart: Double?,
        thermalEnd: Double?,
        energyWh: Double?,
        attempts: Long,
        error: String?,
        userInitiated: Long,
        startedAt: Long?,
        finishedAt: Long?,
    ) = Job(
        id = id,
        mediaId = mediaId,
        runSessionId = runSessionId,
        // Total, like the folder mode and the stop reason: a state this build does not know
        // must not throw on the way into the viewer's info sheet.
        state = JobState.entries.firstOrNull { it.name == state } ?: JobState.FAILED,
        stageBeforePause = stageBeforePause?.let { name -> JobState.entries.firstOrNull { it.name == name } },
        engine = engine,
        setting = setting,
        probes = probes.toInt(),
        xpsnr = xpsnr,
        vmaf = vmaf,
        ssim2 = ssim2,
        originalSize = originalSize,
        newSize = newSize,
        encodeMs = encodeMs,
        verifyMs = verifyMs,
        realtimeMultiple = realtimeMultiple,
        thermalStart = thermalStart?.toFloat(),
        thermalEnd = thermalEnd?.toFloat(),
        energyWh = energyWh,
        attempts = attempts.toInt(),
        userInitiated = userInitiated == 1L,
        error = error,
        startedAt = startedAt?.let(Instant::fromEpochMilliseconds),
        finishedAt = finishedAt?.let(Instant::fromEpochMilliseconds),
    )

    @Suppress("LongParameterList")
    private fun toRunSession(
        id: String,
        startedAt: Long,
        finishedAt: Long?,
        stopReason: String?,
        filesDone: Long,
        filesSkipped: Long,
        filesFailed: Long,
        bytesFreed: Long,
        minutesWorked: Double,
        energyWh: Double,
        thermalPauses: Long,
        filesIndexed: Long,
        duplicatesFound: Long,
        seen: Long,
    ) = RunSession(
        id = id,
        startedAt = startedAt,
        finishedAt = finishedAt,
        // Total, like the folder mode: a stop reason this build does not know must not
        // throw on the way into the one screen that explains what the night did.
        stopReason = stopReason?.let { name -> StopReason.entries.firstOrNull { it.name == name } },
        filesDone = filesDone.toInt(),
        filesSkipped = filesSkipped.toInt(),
        filesFailed = filesFailed.toInt(),
        bytesFreed = bytesFreed,
        minutesWorked = minutesWorked,
        energyWh = energyWh,
        thermalPauses = thermalPauses.toInt(),
        filesIndexed = filesIndexed.toInt(),
        duplicatesFound = duplicatesFound.toInt(),
        seen = seen == 1L,
    )

    /**
     * The five columns `selectSucceededJobs` asks for.
     *
     * `state` is filled in rather than read because the query's WHERE clause already fixed
     * it, and `History.rows` filters on it: reading a column the query cannot vary would be
     * a column this mapper claims to depend on and does not.
     */
    private fun toJob(id: String, mediaId: String, originalSize: Long?, newSize: Long?, finishedAt: Long?) = Job(
        id = id,
        mediaId = mediaId,
        state = JobState.SUCCEEDED,
        originalSize = originalSize,
        newSize = newSize,
        finishedAt = finishedAt?.let(Instant::fromEpochMilliseconds),
    )

    @Suppress("LongParameterList")
    private fun toFolderGrant(
        id: String,
        platformRef: String,
        displayName: String?,
        mode: String,
        offloadRef: String?,
        enabled: Long,
        lastScannedAt: Long?,
    ) = FolderGrant(
        id = id,
        platformRef = MediaRef(platformRef),
        // Not `valueOf`: an unrecognised mode — a row written by a newer build, or a
        // hand-edited database — must not throw on the way into Settings, and KEEP is the
        // reading that can never remove a file.
        mode = FolderMode.entries.firstOrNull { it.name == mode } ?: FolderMode.KEEP,
        displayName = displayName,
        offloadRef = offloadRef?.let(::MediaRef),
        enabled = enabled == 1L,
        lastScannedAt = lastScannedAt,
    )

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

        /**
         * How far back History goes.
         *
         * A cap rather than the whole table: a year of nightly runs is tens of thousands of
         * rows, and a screen nobody scrolls past the first screenful of should not read them
         * all to draw. The totals above are unaffected — they are summed in SQL.
         */
        const val HISTORY_LIMIT = 500L

        /** SCHEMA.md `label.source`: which engine produced it. */
        const val SOURCE = "mlkit"
    }
}
