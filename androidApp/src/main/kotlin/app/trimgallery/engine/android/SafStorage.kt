package app.trimgallery.engine.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.Source
import app.trimgallery.engine.Stat
import app.trimgallery.engine.TempFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Read-only access to the granted folders, plus the app's own scratch space.
 *
 * There is deliberately no `openWrite` here, and this file is deliberately **not** on the
 * `verifySourceBoundaries` allow-list: if a write ever appears in it the build fails.
 * Writing is `SafeReplacerAndroid`'s job and nothing else's (ARCHITECTURE.md § 5, § 14).
 *
 * @param newId mints `MediaItem` ids; UUIDv7 per SCHEMA.md.
 */
class SafStorage(
    private val context: Context,
    private val newId: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LibraryStorage {

    private val resolver: ContentResolver get() = context.contentResolver

    private val tempDir: File
        get() = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }

    /**
     * Walks each granted tree, depth first.
     *
     * A `Flow` rather than a list because a granted DCIM folder can hold tens of
     * thousands of files and the scan runs alongside the night pass; the caller diffs each
     * item into the database as it arrives rather than after the whole walk
     * (ARCHITECTURE.md § 7).
     *
     * What comes out here is only what one cursor query can tell us — name, size, mtime,
     * mime. Codec, resolution, duration and the format flags that decide whether a file may
     * be touched at all live in the file's header, and `ContainerReaderAndroid` reads them
     * for the handful of files `LibraryDiff` found to be new or changed. Reading a header
     * per file here would turn a second into a minute on a hundred-thousand-item library.
     */
    override fun scan(grants: List<FolderGrant>): Flow<MediaItem> = flow {
        grants.filter { it.enabled }.forEach { grant ->
            val tree = Uri.parse(grant.platformRef.value)
            walk(tree, DocumentsContract.getTreeDocumentId(tree)) { emit(it.copy(folderGrantId = grant.id)) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun walk(tree: Uri, documentId: String, emit: suspend (MediaItem) -> Unit) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        resolver.query(children, CHILD_COLUMNS, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val mtime = if (cursor.isNull(4)) 0L else cursor.getLong(4)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(tree, childId, emit)
                    continue
                }

                val kind = kindOf(mime, name) ?: continue
                val now = nowMs()
                emit(
                    MediaItem(
                        id = newId(),
                        platformRef = MediaRef(
                            DocumentsContract.buildDocumentUriUsingTree(tree, childId).toString(),
                        ),
                        name = name,
                        kind = kind,
                        mime = mime,
                        codec = null,
                        width = 0,
                        height = 0,
                        fps = null,
                        bitrate = null,
                        size = size,
                        duration = null,
                        takenAt = null,
                        location = null,
                        cameraModel = null,
                        phash = null,
                        sha256 = null,
                        mtime = mtime,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    /**
     * Size and mtime, and whether the file is still there at all.
     *
     * The snapshot the whole safe-replace contract turns on: taken before the encode,
     * checked again immediately before the swap. Cheap on purpose — one cursor, no decode.
     */
    override suspend fun stat(ref: MediaRef): Stat = withContext(Dispatchers.IO) {
        val uri = Uri.parse(ref.value)
        resolver.query(uri, STAT_COLUMNS, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext MISSING
            Stat(
                size = if (cursor.isNull(0)) 0L else cursor.getLong(0),
                mtime = if (cursor.isNull(1)) 0L else cursor.getLong(1),
                exists = true,
            )
        } ?: MISSING
    }

    override suspend fun openRead(ref: MediaRef): Source = withContext(Dispatchers.IO) {
        val stream = requireNotNull(resolver.openInputStream(Uri.parse(ref.value))) {
            "could not open ${ref.value} for reading"
        }
        StreamSource(stream)
    }

    /**
     * A new app-private scratch file.
     *
     * In `cacheDir`, so that a night killed mid-encode leaves rubbish the system can
     * reclaim under storage pressure rather than a permanently growing directory. Never
     * beside the original — the safe-replace skill lists "writing a temp file into the
     * user's folder" among the things that must never appear.
     */
    override suspend fun tempFile(): TempFile = withContext(Dispatchers.IO) {
        TempFile(File(tempDir, "${newId()}.tmp").absolutePath)
    }

    override suspend fun discard(file: TempFile) {
        withContext(Dispatchers.IO) {
            val path = File(file.path)
            check(path.startsWith(tempDir)) { "refusing to delete $path: it is not app scratch space" }
            path.delete()
        }
    }

    private class StreamSource(private val stream: InputStream) : Source {
        override fun read(into: ByteArray, offset: Int, length: Int): Int = stream.read(into, offset, length)
        override fun close() = stream.close()
    }

    /** Only the kinds BUILD.md § 5 has a pipeline for; anything else is not our business. */
    private fun kindOf(mime: String?, name: String): MediaKind? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            mime == "image/png" || extension == "png" -> MediaKind.PNG
            mime?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            mime?.startsWith("image/") == true || extension in PHOTO_EXTENSIONS -> MediaKind.PHOTO
            else -> null
        }
    }

    private companion object {
        const val TEMP_DIR = "trim-work"
        val MISSING = Stat(size = 0, mtime = 0, exists = false)

        val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val STAT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "3gp", "webm")
        val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "heic", "heif", "webp", "dng", "avif")
    }
}
