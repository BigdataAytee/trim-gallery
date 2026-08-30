package app.trimgallery.engine.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.trimgallery.core.domain.trash.TrashPolicy
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import app.trimgallery.core.pipeline.replace.OffloadMove
import app.trimgallery.core.pipeline.replace.OriginalLocator
import app.trimgallery.core.pipeline.replace.UndoJournal
import app.trimgallery.engine.UndoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Where an original goes when its replacement is committed, and how it comes back.
 *
 * Inside the write boundary (ARCHITECTURE.md § 14): the § 7 contract delegates parking to
 * `UndoStore`, so this file is on the `verifySourceBoundaries` allow-list alongside
 * `SafeReplacerAndroid`. Nothing else in the app may move a user's file.
 *
 * The bin is not a nicety. Compression here is visually lossless, not lossless
 * (PROJECT.md § Quality and reversibility) — the original *is* the undo, which is why
 * nothing in this class ever deletes one that has not first been proved to exist
 * elsewhere.
 */
class UndoBinAndroid(
    private val context: Context,
    private val journal: UndoJournal,
    private val originals: OriginalLocator,
    private val newId: () -> String,
    private val retentionDays: () -> Int = { TrashPolicy.DEFAULT_RETENTION_DAYS },
    private val clock: Clock = Clock.System,
) : UndoStore {

    private val resolver: ContentResolver get() = context.contentResolver

    /** App-owned, on internal storage, so only an uninstall clears it. */
    private val binDir: File
        get() = File(context.filesDir, BIN_DIR).apply { mkdirs() }

    /** The offload target when the folder is in [FolderMode.OFFLOAD]. Its own SAF grant. */
    var offloadTree: Uri? = null

    // ----------------------------------------------------------------- park

    override suspend fun park(ref: MediaRef, mode: UndoLocation): UndoEntry = withContext(Dispatchers.IO) {
        val uri = Uri.parse(ref.value)
        val name = displayNameOf(uri) ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        val size = sizeOf(uri)
        val parkedAt = clock.now()

        val parked = when (mode) {
            UndoLocation.OFFLOAD -> intoOffload(uri, name)
            // SYSTEM_TRASH has no SAF equivalent on Android — `MediaStore.createTrashRequest`
            // needs a user confirmation dialog per call, which a night pass cannot show. The
            // app's own bin is the honest equivalent and is what the UI calls "Recently
            // deleted" (BUILD.md § 6). Recorded in PROJECT.md.
            UndoLocation.BIN, UndoLocation.SYSTEM_TRASH -> intoBin(uri, name)
        }

        UndoEntry(
            id = newId(),
            // Filled in by ReplaceSequence, which is the only caller that knows the plan.
            mediaId = "",
            location = mode,
            ref = MediaRef(parked.toString()),
            originalSize = size.takeIf { it > 0 },
            expiresAt = TrashPolicy.expiresAt(modeFor(mode), parkedAt, retentionDays()),
            createdAt = parkedAt,
        )
    }

    /**
     * Moves the original into the app's bin.
     *
     * Copy-then-delete rather than a rename: the bin is on internal storage and the
     * granted tree is not, so no same-volume rename exists between them. That makes it a
     * cross-volume move, and `OffloadMove` governs the order for the same reason it
     * governs the SD card — never delete before the destination write is confirmed.
     */
    private suspend fun intoBin(uri: Uri, name: String): Uri {
        val destination = File(binDir, "${newId()}__$name")
        return when (
            val moved = OffloadMove(BinOps(destination)).move(
                source = MediaRef(uri.toString()),
                destination = MediaRef(destination.toURI().toString()),
            )
        ) {
            is OffloadMove.Outcome.Moved -> Uri.parse(moved.copy.value)
            is OffloadMove.Outcome.Failed -> error("could not park the original: ${moved.reason}")
        }
    }

    /** SD or USB. Refused rather than guessed when no destination volume is granted. */
    private suspend fun intoOffload(uri: Uri, name: String): Uri {
        val tree = requireNotNull(offloadTree) {
            "offload is selected but no destination volume is granted"
        }
        return when (
            val moved = OffloadMove(TreeOps(tree, name)).move(
                source = MediaRef(uri.toString()),
                destination = MediaRef(tree.toString()),
            )
        ) {
            is OffloadMove.Outcome.Moved -> Uri.parse(moved.copy.value)
            is OffloadMove.Outcome.Failed -> error("could not offload the original: ${moved.reason}")
        }
    }

    // -------------------------------------------------------------- restore

    /**
     * Puts a parked original back under the identity it came from.
     *
     * Two callers, and the difference matters:
     *
     * - **Rollback** inside `ReplaceSequence`, where the commit has already been undone
     *   and the identity is free. It runs under `NonCancellable`, so nothing here may
     *   assume a live scope.
     * - **Restore** from the UI (USER_JOURNEY.md § 5), where the optimised file is sitting
     *   in that identity and has to go.
     *
     * Both are the same sequence, and it is the safe one either way: stage the original
     * back into the tree under a temporary name, confirm the bytes arrived, *then* remove
     * whatever is holding the identity, then rename. The optimised file is the disposable
     * one — it can always be made again — so it is what gets deleted last and first.
     */
    override suspend fun restore(entry: UndoEntry) = withContext(Dispatchers.IO + NonCancellable) {
        val identity = requireNotNull(originals.refFor(entry.mediaId)) {
            "no library row for ${entry.mediaId}; cannot tell where the original belongs"
        }
        val identityUri = Uri.parse(identity.value)
        val parent = requireNotNull(parentOf(identityUri)) { "no parent tree for $identityUri" }
        val name = requireNotNull(displayNameOf(identityUri) ?: nameFromId(identityUri)) {
            "could not work out the original's name"
        }

        val parked = Uri.parse(entry.ref.value)
        val expected = lengthOfParked(parked)

        val staged = requireNotNull(
            DocumentsContract.createDocument(resolver, parent, MIME_ANY, "$STAGING_PREFIX$name"),
        ) { "could not create a staging document to restore into" }

        try {
            openParked(parked).use { input ->
                resolver.openOutputStream(staged, "wt").use { out ->
                    requireNotNull(out) { "could not write the restored original" }
                    input.copyTo(out)
                }
            }
            check(sizeOf(staged) == expected) { "the restored copy is short; leaving the parked original alone" }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, staged) }
            throw e
        }

        // The original is now safely back in the tree under a temporary name. Only now is
        // it safe to remove the optimised file that is holding the name.
        runCatching { DocumentsContract.deleteDocument(resolver, identityUri) }
        DocumentsContract.renameDocument(resolver, staged, name)

        // And only now, with the original in place, is the parked copy redundant.
        runCatching { removeParked(parked) }
        journal.setState(entry, UndoState.RESTORED)
    }

    // ---------------------------------------------------------------- sweep

    /**
     * Deletes originals whose window has closed (BUILD.md § 6, "Free space").
     *
     * `TrashPolicy` decides; this only executes. The row is marked `EXPIRED` rather than
     * deleted so that Restore can tell the user *"the original was removed on <date>"*
     * (USER_JOURNEY.md § 5) instead of silently having nothing to say. The row is marked
     * only after the bytes are actually gone, so a failed delete is retried next sweep
     * rather than being recorded as done.
     */
    override suspend fun sweep(nowEpochMs: Long) = withContext(Dispatchers.IO) {
        val now = Instant.fromEpochMilliseconds(nowEpochMs)
        journal.expiring(nowEpochMs)
            .filter { TrashPolicy.isExpired(it, now) }
            .forEach { entry ->
                val gone = runCatching { removeParked(Uri.parse(entry.ref.value)) }.isSuccess
                if (gone) journal.setState(entry, UndoState.EXPIRED)
            }
    }

    // --------------------------------------------------------------- plumbing

    private fun removeParked(uri: Uri) {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            check(!file.exists() || file.delete()) { "could not delete ${file.name} from the bin" }
        } else {
            DocumentsContract.deleteDocument(resolver, uri)
        }
    }

    private fun openParked(uri: Uri) = if (uri.scheme == "file") {
        File(requireNotNull(uri.path)).inputStream()
    } else {
        requireNotNull(resolver.openInputStream(uri)) { "could not read the parked original" }
    }

    private fun lengthOfParked(uri: Uri): Long =
        if (uri.scheme == "file") File(requireNotNull(uri.path)).length() else sizeOf(uri)

    /** BIN and SYSTEM_TRASH count down; an offloaded original lives on the card until removed. */
    private fun modeFor(location: UndoLocation): FolderMode = when (location) {
        UndoLocation.OFFLOAD -> FolderMode.OFFLOAD
        UndoLocation.BIN, UndoLocation.SYSTEM_TRASH -> FolderMode.FREE
    }

    private fun parentOf(document: Uri): Uri? {
        val documentId = runCatching { DocumentsContract.getDocumentId(document) }.getOrNull() ?: return null
        val cut = documentId.lastIndexOf('/')
        if (cut <= 0) return null
        return DocumentsContract.buildDocumentUriUsingTree(document, documentId.substring(0, cut))
    }

    private fun nameFromId(uri: Uri): String? =
        runCatching { DocumentsContract.getDocumentId(uri).substringAfterLast('/') }.getOrNull()

    private fun displayNameOf(uri: Uri): String? = column(uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    private fun sizeOf(uri: Uri): Long =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L

    private fun column(uri: Uri, name: String): String? = resolver.query(uri, arrayOf(name), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }

    /** Granted tree → app-private bin. */
    private inner class BinOps(private val target: File) : OffloadMove.Ops {
        override suspend fun copy(source: MediaRef, destination: MediaRef): MediaRef {
            requireNotNull(resolver.openInputStream(Uri.parse(source.value))) {
                "could not read the original"
            }.use { input -> target.outputStream().use { input.copyTo(it) } }
            return MediaRef(target.toURI().toString())
        }

        override suspend fun verify(source: MediaRef, copy: MediaRef): Boolean {
            val expected = sizeOf(Uri.parse(source.value))
            return expected > 0 && target.length() == expected
        }

        override suspend fun removeCopy(copy: MediaRef) {
            target.delete()
        }

        override suspend fun removeSource(source: MediaRef) {
            DocumentsContract.deleteDocument(resolver, Uri.parse(source.value))
        }
    }

    /** Granted tree → a second granted tree on removable storage. */
    private inner class TreeOps(private val tree: Uri, private val name: String) : OffloadMove.Ops {
        override suspend fun copy(source: MediaRef, destination: MediaRef): MediaRef {
            val created = requireNotNull(
                DocumentsContract.createDocument(resolver, tree, MIME_ANY, name),
            ) { "could not create a document on the offload volume" }

            requireNotNull(resolver.openInputStream(Uri.parse(source.value))) {
                "could not read the original"
            }.use { input ->
                resolver.openOutputStream(created, "wt").use { out ->
                    requireNotNull(out) { "could not write to the offload volume" }
                    input.copyTo(out)
                }
            }
            return MediaRef(created.toString())
        }

        /**
         * Sizes on both sides, read back from the destination provider rather than from
         * the stream just written.
         *
         * Counterfeit cards report a write as complete and silently drop the data past
         * their real capacity; asking the provider what it actually holds is the only way
         * to notice before the original is deleted.
         */
        override suspend fun verify(source: MediaRef, copy: MediaRef): Boolean {
            val expected = sizeOf(Uri.parse(source.value))
            return expected > 0 && expected == sizeOf(Uri.parse(copy.value))
        }

        override suspend fun removeCopy(copy: MediaRef) {
            runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(copy.value)) }
        }

        override suspend fun removeSource(source: MediaRef) {
            DocumentsContract.deleteDocument(resolver, Uri.parse(source.value))
        }
    }

    private companion object {
        const val BIN_DIR = "undo-bin"
        const val MIME_ANY = "application/octet-stream"
        const val STAGING_PREFIX = ".trim-restoring-"
    }
}
