package app.trimgallery.engine.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.media.MediaScannerConnection
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.pipeline.replace.Committed
import app.trimgallery.core.pipeline.replace.ReplaceOps
import app.trimgallery.core.pipeline.replace.ReplaceSequence
import app.trimgallery.core.pipeline.replace.UndoJournal
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.MetadataCopier
import app.trimgallery.engine.ReplacePlan
import app.trimgallery.engine.ReplaceResult
import app.trimgallery.engine.Replacer
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.UndoStore
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only class on Android that writes to a folder the user granted.
 *
 * ARCHITECTURE.md § 14 enforces that with a build guard: `verifySourceBoundaries` fails
 * the build if `DocumentsContract.renameDocument`, `openOutputStream`, or a `DocumentFile`
 * mutation appears in any file other than this one and its three siblings. So every SAF
 * write in the app is in this file, deliberately — the platform mechanics are here and
 * the *ordering* is in `ReplaceSequence`, which is shared and unit tested.
 *
 * @see app.trimgallery.core.pipeline.replace.ReplaceSequence for the § 7 contract itself.
 */
class SafeReplacerAndroid(
    private val context: Context,
    storage: LibraryStorage,
    metadata: MetadataCopier,
    undo: UndoStore,
    journal: UndoJournal,
) : Replacer {

    private val resolver: ContentResolver get() = context.contentResolver

    private val sequence = ReplaceSequence(
        storage = storage,
        metadata = metadata,
        undo = undo,
        journal = journal,
        ops = SafOps(),
    )

    override suspend fun replace(plan: ReplacePlan): ReplaceResult = sequence.replace(plan)

    /**
     * The SAF half of the contract.
     *
     * A nested class rather than its own file so that every write stays inside the one
     * file the guard's allow-list names. Splitting it out would mean widening the
     * allow-list, and an allow-list that grows to fit the code stops being a guard.
     */
    private inner class SafOps : ReplaceOps {

        /**
         * Puts the replacement into the library under the original's name and directory.
         *
         * Two steps, because the temp file is app-private and usually on a different
         * volume from the granted tree, so there is no rename that can do it in one:
         *
         * 1. create a document in the original's parent under a scratch name and stream
         *    the bytes in — a partial write is visible as an obviously-temporary file
         *    rather than as a corrupt photo;
         * 2. rename it onto the original's name, which is atomic within the volume.
         *
         * The original is already parked by the time this runs, so step 2 is renaming
         * onto a free name. Renaming over an occupied one either fails or silently
         * invents `VID_0001 (1).mp4`, which is why the ordering in `ReplaceSequence` is
         * not negotiable.
         */
        override suspend fun commit(replacement: TempFile, under: MediaRef): Committed =
            withContext(Dispatchers.IO) {
                val originalUri = Uri.parse(under.value)
                val parent = requireNotNull(parentOf(originalUri)) {
                    "no parent tree for $originalUri; the grant cannot be written to"
                }
                val displayName = requireNotNull(displayNameOf(originalUri)) {
                    "could not read the original's display name"
                }

                val staged = requireNotNull(
                    DocumentsContract.createDocument(resolver, parent, mimeFor(displayName), stagedName(displayName)),
                ) { "could not create the replacement document" }

                val source = File(replacement.path)
                val written = try {
                    resolver.openOutputStream(staged, "wt").use { out ->
                        requireNotNull(out) { "could not open the staged document for writing" }
                        source.inputStream().use { it.copyTo(out) }
                    }
                    // Length as the tree reports it, not as the local file claims: a card
                    // that quietly dropped the tail must not be mistaken for a good write.
                    lengthOf(staged)
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(resolver, staged) }
                    throw e
                }

                check(written == source.length()) {
                    "staged document is $written B against a ${source.length()} B replacement"
                }

                val committed = requireNotNull(
                    DocumentsContract.renameDocument(resolver, staged, displayName),
                ) { "could not rename the replacement onto the original's name" }

                Committed(MediaRef(committed.toString()), written)
            }

        override suspend fun uncommit(committed: Committed) = withContext(Dispatchers.IO) {
            DocumentsContract.deleteDocument(resolver, Uri.parse(committed.ref.value))
            Unit
        }

        /**
         * Puts the original's modification time back on the replacement.
         *
         * Without this the user's whole library sorts to "just now" the morning after the
         * first night — the most visible damage this app could do without losing a byte.
         *
         * `COLUMN_LAST_MODIFIED` is documented as read-only for most providers, so a
         * failure here is not fatal on its own; what makes the date survive regardless is
         * `MetadataCopierAndroid`, which writes the capture time into the file's own
         * metadata before the commit. The gallery sorts on that.
         */
        override suspend fun restoreTimestamps(committed: Committed, mtime: Long) {
            withContext(Dispatchers.IO) {
                val values = android.content.ContentValues().apply {
                    put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, mtime)
                }
                runCatching { resolver.update(Uri.parse(committed.ref.value), values, null, null) }
            }
        }

        /**
         * Makes the swap visible to every other app.
         *
         * Renaming over the original path usually preserves the MediaStore row
         * (PROJECT.md § Codec facts); the rescan is what makes it certain, and what
         * updates the size the system gallery shows. Bounded, because
         * `MediaScannerConnection` can simply never call back on some devices and a night
         * pass must not hang on one file.
         */
        override suspend fun notifyLibrary(committed: Committed) {
            val path = pathOf(Uri.parse(committed.ref.value)) ?: return
            withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
    }

    /**
     * The tree URI of a document's parent.
     *
     * `buildDocumentUriUsingTree` with the parent's own document id: SAF has no "parent
     * of" call, so the id is derived from the child's, which is the documented shape for
     * tree documents.
     */
    private fun parentOf(document: Uri): Uri? {
        val documentId = DocumentsContract.getDocumentId(document)
        val cut = documentId.lastIndexOf('/')
        if (cut <= 0) return null
        return DocumentsContract.buildDocumentUriUsingTree(document, documentId.substring(0, cut))
    }

    private fun displayNameOf(uri: Uri): String? = queryString(uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    private fun lengthOf(uri: Uri): Long =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        } ?: 0L

    private fun queryString(uri: Uri, column: String): String? =
        resolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }

    /**
     * A filesystem path for the media scanner, or null when the grant has none.
     *
     * The safe-replace skill is explicit that some grants have no usable `File`; this
     * returns null there rather than inventing one, and the scan is skipped.
     */
    private fun pathOf(uri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2 || parts[0] != PRIMARY_VOLUME) return null
        @Suppress("DEPRECATION")
        return File(android.os.Environment.getExternalStorageDirectory(), parts[1]).absolutePath
    }

    /** An obviously-temporary name, so an interrupted write is never mistaken for a photo. */
    private fun stagedName(displayName: String): String = "$STAGING_PREFIX$displayName"

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "jpg", "jpeg" -> "image/jpeg"
        "heic", "heif" -> "image/heic"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    private companion object {
        const val STAGING_PREFIX = ".trim-staging-"
        const val PRIMARY_VOLUME = "primary"
        const val SCAN_TIMEOUT_MS = 10_000L
    }
}
