package app.trimgallery.core.pipeline

import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.GeoPoint
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.SkipReason
import app.trimgallery.engine.CodecCaps
import app.trimgallery.engine.ContainerFacts
import app.trimgallery.engine.ContainerReader
import app.trimgallery.engine.LibraryStorage
import kotlinx.coroutines.flow.toList

/**
 * The front of the night: scan, diff, triage, write the verdicts (BUILD.md § 13.6).
 *
 * ARCHITECTURE.md § 7 puts `storage.scan(grants) → DB diff` at the top of the pass and
 * ARCHITECTURE.md § 15 assigns the whole of milestone 6 to shared code, which is why this
 * knows nothing about SAF or PhotoKit: it takes a `LibraryStorage`, a set of grants and a
 * place to write, and every rule it applies is in `LibraryDiff` and `Triager`.
 *
 * Nothing here writes to the user's library. Triage is metadata only — BUILD.md § 5 is
 * explicit that no file is opened and no frame decoded — because this runs over the whole
 * library every night and has to cost nothing.
 */
class TriageStep(
    private val storage: LibraryStorage,
    private val containers: ContainerReader,
    private val sink: Sink,
    private val nowMs: () -> Long,
) {

    /** Where verdicts go. Implemented by the repository; kept narrow so it can be faked. */
    interface Sink {
        /** Every row the database currently holds for [grants]. */
        suspend fun stored(grants: List<FolderGrant>): List<MediaItem>

        /** A file the library has that the database did not. */
        suspend fun insert(item: MediaItem)

        /** A file whose bytes moved. Carries the merged row, already reset to `NEW`. */
        suspend fun update(item: MediaItem)

        /**
         * A file that is gone.
         *
         * Deleting the row, not the file — there is nothing left to delete. An item with a
         * live undo entry is the one exception the caller must handle: the original is
         * still in the bin and the row is what points at it.
         */
        suspend fun remove(item: MediaItem)

        /** The verdict: status, reason and the queue's ordering key. */
        suspend fun recordVerdict(item: MediaItem, status: MediaStatus, reason: SkipReason?, estSaving: Long?)
    }

    /** What one triage pass did, for the log and for the "nothing to do" case. */
    data class Report(
        val scanned: Int,
        val added: Int,
        val modified: Int,
        val removed: Int,
        val candidates: Int,
        val skipped: Map<SkipReason, Int>,
    ) {
        /** USER_JOURNEY.md § 14: *"Everything's already efficient — nothing to do tonight."* */
        val nothingToDo: Boolean get() = candidates == 0
    }

    /**
     * @param caps this device's encoder limits, or null if not yet queried. Passing them
     *   turns a file the phone could never encode into a skip with a reason, rather than a
     *   probe, a search, a full encode and a failure.
     */
    suspend fun run(grants: List<FolderGrant>, caps: CodecCaps? = null): Report {
        val enabled = grants.filter { it.enabled }
        val scanned = storage.scan(enabled).toList()
        val stored = sink.stored(enabled)

        val diff = LibraryDiff.diff(
            stored = stored,
            scanned = scanned,
            scannedGrants = enabled.map { it.id }.toSet(),
        )

        diff.removed.forEach { sink.remove(it) }

        var candidates = 0
        val skipped = mutableMapOf<SkipReason, Int>()

        // Only what changed gets a container read and a verdict. Re-triaging the whole
        // library every night would give the same answer for everything that did not move,
        // at a cost that grows with the library — and BUILD.md § 5 is emphatic that triage
        // has to be the cheap step. The scan above is one cursor query over thousands of
        // rows; opening every one of those files to read its header would turn a second
        // into a minute.
        diff.added.forEach { row ->
            val item = enrich(row)
            sink.insert(item)
            candidates += record(item, caps, skipped)
        }

        diff.modified.forEach { change ->
            val item = enrich(LibraryDiff.merge(change.stored, change.scanned, nowMs()))
            sink.update(item)
            candidates += record(item, caps, skipped)
        }

        return Report(
            scanned = scanned.size,
            added = diff.added.size,
            modified = diff.modified.size,
            removed = diff.removed.size,
            candidates = candidates,
            skipped = skipped,
        )
    }

    private suspend fun enrich(item: MediaItem): MediaItem = containers.read(item.platformRef)?.applyTo(item) ?: item

    /** @return 1 if the item is a candidate, 0 if it was skipped. */
    private suspend fun record(item: MediaItem, caps: CodecCaps?, skipped: MutableMap<SkipReason, Int>): Int =
        when (val verdict = Triager.triage(item, caps)) {
            is Triager.Verdict.Candidate -> {
                sink.recordVerdict(item, MediaStatus.CANDIDATE, reason = null, estSaving = verdict.estimatedSaving)
                1
            }

            is Triager.Verdict.Skip -> {
                skipped[verdict.reason] = (skipped[verdict.reason] ?: 0) + 1
                sink.recordVerdict(item, MediaStatus.SKIPPED, reason = verdict.reason, estSaving = null)
                0
            }
        }
}

/**
 * Folds container metadata onto the row the scan produced.
 *
 * The scan knows what SAF or PhotoKit can tell it from a cursor — name, size, mtime, mime.
 * Everything else that decides a verdict is in the file's header, and this is where the two
 * meet.
 */
fun ContainerFacts.applyTo(item: MediaItem): MediaItem = item.copy(
    codec = codec ?: item.codec,
    width = if (width > 0) width else item.width,
    height = if (height > 0) height else item.height,
    fps = fps ?: item.fps,
    bitrate = bitrate ?: item.bitrate,
    duration = durationMs ?: item.duration,
    // Container flags only: favourite and hidden are the user's, and live in the same
    // bitmask (SCHEMA.md).
    flags = flags.copy(favourite = item.flags.favourite, hidden = item.flags.hidden),
    // A file with no camera model but a recognisable encoder is its own predictor family,
    // rather than being lumped into "unknown" with every other metadata-less file.
    cameraModel = cameraModel ?: writer ?: item.cameraModel,
    takenAt = takenAtEpochMs?.let { kotlin.time.Instant.fromEpochMilliseconds(it) } ?: item.takenAt,
    // Bound to locals rather than smart-cast: `latitude` and `longitude` are public API
    // properties of a class in another module, and Kotlin will not smart-cast across a
    // module boundary — another module could, in principle, make the property a `var`.
    location = latitude?.let { lat -> longitude?.let { lon -> GeoPoint(lat, lon) } } ?: item.location,
)
