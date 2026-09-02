package app.trimgallery.engine.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.trimgallery.core.data.TrimRepository
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
 *
 * `open`, and only for [grants] and [take], so an emulator test can stand in the one place
 * this app cannot drive: the system folder picker. Everything downstream of the picker's
 * result — taking the grant, scheduling the night pass, recording the grant row, the scan,
 * the grid — is the app's own code and runs for real in `GalleryJourneyTest`. Faking the
 * platform's answer is the only way to reach it; faking anything more would be testing the
 * test.
 */
open class GrantedFolders(private val context: Context) {

    /**
     * Every readable persisted grant, newest first.
     *
     * [FolderMode.KEEP] for all of them for now, which is the mode that never deletes
     * anything: BUILD.md § 6 has three modes and only the settings screen can ask the
     * user which they want, so until that screen exists the safe one is the honest
     * default. OFFLOAD and FREE both move or expire originals, and neither should
     * happen because nobody has been asked.
     */
    open fun grants(): List<FolderGrant> = context.contentResolver.persistedUriPermissions
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
    open fun take(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /**
     * Gives every granted folder back.
     *
     * The escape hatch from a crash loop: a grant the app cannot scan without dying is a
     * grant it must be able to drop without the user going to system Settings — which they
     * cannot reach *through the app*, because the app is not staying open long enough.
     *
     * Only the permission is released. Nothing in the user's folder is touched, and nothing
     * can be: this class has no write path and `SafStorage` has no `openWrite`
     * (ARCHITECTURE.md § 2.2). The rows in our own database are left alone too — a
     * `folder_grant` row for a folder nobody has granted simply never joins, and re-granting
     * the folder restores whatever mode was chosen for it.
     */
    /**
     * Gives one folder's permission back.
     *
     * The same contract as [releaseAll] and for the same reasons: only the permission goes.
     * Nothing in the user's folder is touched, nothing can be, and the `folder_grant` row
     * is left alone so re-granting the folder restores the mode that was chosen for it.
     *
     * `open` so an instrumented test can grant and revoke without a system picker.
     */
    open fun release(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun releaseAll() {
        context.contentResolver.persistedUriPermissions.forEach { permission ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    permission.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    /**
     * The granted folders with the mode the user chose for each.
     *
     * The join this class's whole design implies: the platform says *which* folders are
     * granted, the database says what to do with the originals inside them, and the tree
     * URI is the key both sides share. A stored row for a folder that is no longer granted
     * simply never joins — harmless, and it means re-granting restores the choice.
     *
     * Suspending, because it reads the database. [grants] stays synchronous for the
     * callers that only need to know whether anything is granted at all — the scan and the
     * night-pass scheduler — and neither of those cares about the mode.
     *
     * **Known gap:** the night pass therefore still treats every folder as
     * [FolderMode.KEEP] until the replace path reads this. That is the safe direction —
     * KEEP never removes an original — but it does mean choosing Offload or Free here does
     * not yet change what the night pass does. Recorded in PROJECT.md.
     */
    suspend fun withModes(repository: TrimRepository): List<FolderGrant> = grants().map { grant ->
        val stored = repository.folderGrant(grant.platformRef.value) ?: return@map grant
        // The platform's name wins *when it has one*: it is read live, so a folder renamed
        // since the row was written shows its current name. When the provider refuses a
        // name — which [displayName] catches rather than throws — the stored one is still
        // better than none.
        stored.copy(displayName = grant.displayName ?: stored.displayName)
    }

    /** The folder's own name, for the UI. Null when the provider will not give one. */
    private fun displayName(uri: Uri): String? = runCatching {
        DocumentFile.fromTreeUri(context, uri)?.name
    }.getOrNull()
}
