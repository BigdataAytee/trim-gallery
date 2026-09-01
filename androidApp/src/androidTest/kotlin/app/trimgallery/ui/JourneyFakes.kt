package app.trimgallery.ui

import android.content.Context
import android.net.Uri
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaRef
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.NightConstraints
import app.trimgallery.engine.NightScheduler
import app.trimgallery.engine.TempFile
import app.trimgallery.engine.android.GrantedFolders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The three edges `GalleryJourneyTest` stands in for, and nothing else.
 *
 * Each of these is a place the app talks to something an emulator cannot supply: the system
 * folder picker's grant, a tree of the user's own files, and WorkManager. Everything between
 * them — the picker's result handling, the grant row, the scan, the diff, the database, the
 * grid, the viewer, the player — is the real thing, because a journey test that fakes the
 * middle proves only that the fakes agree with each other.
 */

/**
 * A folder grant without a folder picker.
 *
 * [GrantedFolders] reads `getPersistedUriPermissions`, which an emulator test cannot write
 * to: taking a persistable permission needs a real `ACTION_OPEN_DOCUMENT_TREE` result from
 * the system picker, and the picker is a different app's UI. So this answers as the platform
 * would have, and the app's own code from the result onwards runs untouched.
 */
internal class FakeGrantedFolders(context: Context, initial: List<FolderGrant> = emptyList()) :
    GrantedFolders(context) {

    var granted: List<FolderGrant> = initial
        private set

    override fun grants(): List<FolderGrant> = granted

    /** As the real one does: the tree URI is both the grant's id and its platform ref. */
    override fun take(uri: Uri) {
        granted = granted + FolderGrant(
            id = uri.toString(),
            platformRef = MediaRef(uri.toString()),
            mode = FolderMode.KEEP,
            displayName = "Journey",
        )
    }
}

/**
 * A library of exactly the files the test wrote, walked as SAF would walk them.
 *
 * [gate] is the interesting part. Held closed, the walk never emits, so anything the grid
 * draws must have come from the database — which is how the fast start is asserted rather
 * than assumed. `SafStorage` cannot be used here for the same reason the grants are faked:
 * without a persisted permission there is no tree to walk.
 */
internal class FakeLibrary(private val items: List<MediaItem>) : LibraryStorage {

    /** Set to hold the walk shut until the test opens it. Null means "emit immediately". */
    var gate: CompletableDeferred<Unit>? = null

    override fun scan(grants: List<FolderGrant>): Flow<MediaItem> = flow {
        gate?.await()
        items.forEach { emit(it) }
    }

    // The gallery journeys open no file contents: thumbnails go through the platform, and
    // nothing here encodes. Throwing rather than returning something empty means a journey
    // that quietly starts reading bytes fails loudly instead of passing on a fiction.
    override suspend fun stat(ref: MediaRef) = unused()

    override suspend fun openRead(ref: MediaRef) = unused()

    override suspend fun tempFile() = unused()

    override suspend fun writeTemp(bytes: ByteArray) = unused()

    override suspend fun discard(file: TempFile) = unused()

    private fun unused(): Nothing = error("the gallery journeys never read or write file contents")
}

/** WorkManager, reduced to the one question the journeys ask: was the night pass scheduled? */
internal class RecordingScheduler : NightScheduler {

    var scheduled: Boolean = false
        private set

    override fun schedule(constraints: NightConstraints) {
        scheduled = true
    }

    override fun cancel() {
        scheduled = false
    }
}
