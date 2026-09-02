package app.trimgallery.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.SettingsStore
import app.trimgallery.engine.android.FolderChoice
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.NightPass
import app.trimgallery.feature.settings.FolderRow
import app.trimgallery.feature.settings.FoldersScreen
import app.trimgallery.feature.settings.WholePhoneExplainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import android.provider.Settings as AndroidSettings

/**
 * The folders this app may read, and what happens to the originals in each.
 *
 * Split out of Settings by the pivot. In a gallery a granted folder was setup; in a utility
 * it is the product — nothing is read and nothing runs until one exists, and the mode
 * chosen here decides whether an original is kept, moved to another drive, or eventually
 * deleted for good.
 */
@Composable
fun FoldersHost(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    folders: GrantedFolders = koinInject(),
    repository: TrimRepository = koinInject(),
    nightPass: NightPass = koinInject(),
    store: SettingsStore = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = TrimTheme.colors

    var rows by remember { mutableStateOf(emptyList<FolderRow>()) }
    // Bumped whenever something changed underneath the list: a grant taken or given back,
    // a mode saved.
    var revision by remember { mutableStateOf(0) }
    var explaining by remember { mutableStateOf(false) }
    // Read once for the picker rather than collected: it is only consulted at the moment a
    // grant lands, and a switch flipped on another screen while this one is open would not
    // change what that grant should do.
    var nightPassEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { nightPassEnabled = store.read().nightPassEnabled }
    LaunchedEffect(revision) { rows = folderRows(folders, repository) }

    val picker = rememberFolderPicker(
        folders = folders,
        nightPass = nightPass,
        nightPassEnabled = { nightPassEnabled },
    ) { revision++ }

    Box(modifier.fillMaxSize().background(colors.page)) {
        if (explaining) {
            WholePhoneExplainer(
                onContinue = {
                    explaining = false
                    context.startActivity(allFilesAccessIntent(context.packageName))
                },
                onCancel = { explaining = false },
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
            )
        } else {
            FoldersScreen(
                folders = rows,
                onFolderMode = { row, mode ->
                    scope.launch {
                        repository.saveFolderGrant(row.toGrant(mode, folders))
                        revision++
                    }
                },
                onAddFolder = { picker.choose() },
                onRemoveFolder = { row ->
                    scope.launch {
                        withContext(Dispatchers.IO) { folders.release(Uri.parse(row.ref)) }
                        // The schedule follows the grants: giving back the last folder must
                        // not leave a job waking the phone nightly to read nothing.
                        nightPass.sync(enabled = nightPassEnabled)
                        revision++
                    }
                },
                onWholePhone = { explaining = true },
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
                header = { BackToHome(onBack) },
            )
        }
        picker.HelpSheet()
    }
}

/**
 * Android's own All-files-access screen, for this app.
 *
 * The app-scoped action first, so the user lands on Trim's own row rather than a list of
 * every installed app. Falling back to the list is better than falling back to nothing on a
 * device whose OEM has removed the scoped screen.
 */
private fun allFilesAccessIntent(packageName: String): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Intent(
        AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:$packageName"),
    )
} else {
    Intent(AndroidSettings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
}

@Composable
private fun BackToHome(onBack: () -> Unit) {
    BasicText(
        text = "← Home",
        style = TrimTheme.typography.label.copy(color = TrimTheme.colors.accent),
        modifier = Modifier.pressScale(onBack),
    )
}

/**
 * The granted folders as the screen wants them, joined to the mode the user chose.
 *
 * Suspending and called from a [LaunchedEffect] rather than read during composition:
 * `GrantedFolders.grants` asks the content resolver and `withModes` asks the database, and
 * neither belongs on a frame.
 */
private suspend fun folderRows(folders: GrantedFolders, repository: TrimRepository): List<FolderRow> =
    withContext(Dispatchers.IO) {
        // Off the main thread deliberately: `grants` asks the content resolver and then asks
        // a document provider for each folder's name, which is an IPC round trip per folder.
        val grants = folders.withModes(repository)
        grants.map { grant ->
            FolderRow(
                ref = grant.platformRef.value,
                // Percent-decoded before the last segment is taken: a tree URI ends
                // `/tree/primary%3ADCIM%2FCamera`, so the raw string's last segment is the
                // whole encoded document id rather than the folder's name.
                displayName = grant.displayName ?: Uri.decode(grant.platformRef.value).substringAfterLast('/'),
                mode = grant.mode,
                offloadTarget = offloadTargetFor(grant, grants)?.let { it.displayName ?: "the other drive" },
            )
        }
    }

/**
 * A granted folder on a *different* volume, which is the only thing offload can use.
 *
 * Different volume, not merely a different folder: offload exists to get the originals off
 * a drive that is full, and copying them somewhere else on the same drive frees nothing.
 * `FolderChoice.volumeOf` reads the volume out of the tree's document id — `primary` for
 * internal storage, a filesystem UUID for an SD card or a USB drive.
 *
 * The first match wins when there are several. That is a real limitation: somebody with two
 * removable drives cannot yet say which one, and choosing needs a picker this screen does
 * not have. It is recorded in PROJECT.md, and it is safe in the meantime because nothing
 * moves an original until the replace path reads these modes.
 */
private fun offloadTargetFor(folder: FolderGrant, all: List<FolderGrant>): FolderGrant? {
    val volume = FolderChoice.volumeOf(Uri.parse(folder.platformRef.value)) ?: return null
    return all.firstOrNull { other ->
        // A volume this app cannot read is not treated as a different drive. Guessing here
        // would offer to move originals somewhere that might be the same disk.
        val otherVolume = FolderChoice.volumeOf(Uri.parse(other.platformRef.value))
        otherVolume != null && !otherVolume.equals(volume, ignoreCase = true)
    }
}

/**
 * The row plus a newly chosen mode, as the database row to write.
 *
 * The offload destination is resolved again here rather than carried on the row, because
 * what gets stored has to be the tree URI the replacer will open — the row only carries the
 * drive's *name*, which is for reading, not for opening. A mode other than OFFLOAD clears
 * it: a stale destination left on a folder set back to Keep is a trap for whoever reads the
 * row next.
 */
private suspend fun FolderRow.toGrant(mode: FolderMode, folders: GrantedFolders): FolderGrant =
    withContext(Dispatchers.IO) {
        val grants = folders.grants()
        val mine = grants.firstOrNull { it.platformRef.value == ref }
        FolderGrant(
            // Empty means "the repository mints one". The upsert keys on the tree URI, so
            // an existing row keeps the id it already has.
            id = "",
            platformRef = MediaRef(ref),
            mode = mode,
            displayName = mine?.displayName ?: displayName,
            offloadRef = if (mode == FolderMode.OFFLOAD && mine != null) {
                offloadTargetFor(mine, grants)?.platformRef
            } else {
                null
            },
        )
    }
