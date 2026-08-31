package app.trimgallery.engine.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaRef

/**
 * The folders the user has granted, read from the platform rather than from our database.
 *
 * `ContentResolver.getPersistedUriPermissions()` *is* the record of what this app may
 * read: it survives reboot, it survives the database being cleared, and it is the only
 * thing that decides whether a scan will actually succeed. A row in our own tables can
 * disagree with it — the user can revoke a grant in Settings and we would never hear —
 * and a grant we think we have but do not is a scan that fails at the first cursor.
 *
 * So the platform is the source of truth here, and the database will hold the *settings*
 * that hang off a grant (its folder mode, when it was last scanned) once the settings
 * screen exists to change them. Recorded in PROJECT.md.
 */
class GrantedFolders(private val context: Context) {

    /**
     * Every readable persisted grant, newest first.
     *
     * [FolderMode.KEEP] for all of them for now, which is the mode that never deletes
     * anything: BUILD.md § 6 has three modes and only the settings screen can ask the
     * user which they want, so until that screen exists the safe one is the honest
     * default. OFFLOAD and FREE both move or expire originals, and neither should
     * happen because nobody has been asked.
     */
    fun grants(): List<FolderGrant> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }
        .sortedByDescending { it.persistedTime }
        .map { permission ->
            FolderGrant(
                // The tree URI is already a stable, unique identity for the grant, and it
                // is what `SafStorage.scan` resolves. Minting a UUID here would create a
                // second identity that has to be kept in step with the first.
                id = permission.uri.toString(),
                platformRef = MediaRef(permission.uri.toString()),
                mode = FolderMode.KEEP,
                displayName = displayName(permission.uri),
            )
        }

    /**
     * Persists a tree the user just picked.
     *
     * Read **and** write: the write half is not used until `SafeReplacerAndroid` commits
     * a replacement, but the permission has to have been taken at grant time — it cannot
     * be widened later without asking the user again, and discovering that on the night
     * of the first replace would strand a verified encode with nowhere to go.
     */
    fun take(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** The folder's own name, for the UI. Null when the provider will not give one. */
    private fun displayName(uri: Uri): String? = runCatching {
        DocumentFile.fromTreeUri(context, uri)?.name
    }.getOrNull()
}
