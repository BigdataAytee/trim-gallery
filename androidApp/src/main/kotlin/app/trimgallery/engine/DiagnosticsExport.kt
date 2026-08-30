package app.trimgallery.engine.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.trimgallery.core.domain.field.Diagnostics
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Export diagnostics" on Android (LAUNCH.md § Support, USER_JOURNEY.md § 13 under Privacy).
 *
 * Writes the report `Diagnostics` produced into an app-private directory and hands back a
 * share intent. Nothing here decides *what* goes in the file — that is
 * `Diagnostics`, in shared code, with the redaction tests — and nothing here sends
 * anything: the app has no `INTERNET` permission and a build guard enforces it, so the file
 * moves only if the user picks somewhere for it to go in the system sheet.
 *
 * Two details that are the point rather than plumbing:
 *
 * - **Its own subdirectory**, not the cache root. The cache root also holds the encoder's
 *   temp files, which are copies of the user's originals mid-optimisation. A provider
 *   pointed at the root would make a granted URI for one of those reachable.
 * - **The previous export is deleted first.** A diagnostics file is a snapshot of a
 *   moment, and a directory quietly accumulating months of them is a pile of the user's
 *   measurements sitting on their phone for no reason — and a bigger surface for the next
 *   mistake to expose.
 */
class DiagnosticsExport(private val context: Context) {

    /**
     * Writes the report and returns an intent that offers it to the share sheet.
     *
     * The caller starts it, with `FLAG_GRANT_READ_URI_PERMISSION` already set: the grant is
     * per share, for this one file, and expires with the activity that receives it.
     */
    suspend fun share(report: String, fileName: String = DEFAULT_NAME): Intent = withContext(Dispatchers.IO) {
        val file = write(report, fileName)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)

        Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Writes the file and returns it, for a caller that wants to show it before sharing. */
    suspend fun write(report: String, fileName: String = DEFAULT_NAME): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        // One at a time: an export is a snapshot, and a folder collecting months of them is
        // the user's measurements kept for nobody's benefit.
        directory.listFiles()?.forEach { it.delete() }
        File(directory, fileName).apply { writeText(report) }
    }

    /** Removes anything left behind, for the Settings screen's "delete exported file". */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        File(context.cacheDir, DIRECTORY).listFiles()?.forEach { it.delete() }
        Unit
    }

    private companion object {
        /** Must match `res/xml/diagnostics_paths.xml`. */
        const val DIRECTORY = "diagnostics"
        const val DEFAULT_NAME = "trim-diagnostics.txt"
        const val MIME = "text/plain"
        const val SUBJECT = "Trim Gallery diagnostics"
    }
}
