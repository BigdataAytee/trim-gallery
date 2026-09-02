package app.trimgallery.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Serves the journey's files over `content://`, the way a document provider does.
 *
 * The emulator has no SAF picker and no real granted tree, so the suite has always handed
 * the app `file://` URIs instead — and `JourneyFixtures` names that as "the one seam". It is
 * the seam the reported crash lives on: a `content://` URI and a `file://` URI take
 * different code paths through every reader in this app.
 *
 *   * **`ContentResolver.openInputStream`** is the only way to read a `content://`, where a
 *     `file://` can be opened as a path — a different set of failures for the same bytes.
 *   * **`MediaMetadataRetriever`** has `setDataSource(String)` for a path and
 *     `setDataSource(Context, Uri)` for a URI; only the second consults a provider.
 *   * **ExoPlayer** builds a `FileDataSource` for one and a `ContentDataSource` for the
 *     other.
 *
 * Deliberately minimal, and deliberately *not* generous: it answers `openFile`, `getType`
 * and the two `OpenableColumns` a caller may ask for, and returns null for everything else
 * — which is what a real provider does, and what code written against a filesystem tends to
 * assume it never will.
 */
class JourneyDocumentsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * The file a document URI names.
     *
     * The last path segment is the file's name inside the app's cache directory. A real SAF
     * document id is opaque and this one is too, as far as the app is concerned: it never
     * parses it, it only hands the whole URI back to a resolver.
     */
    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val context = context ?: return null
        return File(context.cacheDir, name).takeIf { it.isFile }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        // Read-only, always. ARCHITECTURE.md § 2.2 says originals are read-only until the
        // one replace in `SafeReplacerAndroid`, and a provider that granted "w" here would
        // let the suite pass code a real provider would refuse.
        require(mode == "r") { "the journey provider is read-only, asked for '$mode'" }
        val file = fileFor(uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when {
        uri.lastPathSegment?.endsWith(".mp4") == true -> "video/mp4"
        uri.lastPathSegment?.endsWith(".jpg") == true -> "image/jpeg"
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = fileFor(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
