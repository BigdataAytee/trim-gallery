package app.trimgallery.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.SettingsStore
import app.trimgallery.engine.android.BuildIdentity
import app.trimgallery.engine.android.FolderChoice
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.NightPass
import app.trimgallery.feature.settings.FolderRow
import app.trimgallery.feature.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Settings, bound to the two things that hold its state.
 *
 * They are deliberately two. The **platform** owns which folders are granted — persisted
 * URI permissions survive the database being cleared and are the only thing that decides
 * whether a scan will succeed — and the **database** owns what to do with the originals
 * inside them, keyed on the tree URI both sides share. So a folder disappears from this
 * list when the user revokes it in system Settings, and its mode comes back if they ever
 * grant it again.
 *
 * Everything the screen itself needs is a value or a callback; the screen names no
 * platform, which is what keeps it compiling for iOS.
 */
@Composable
fun SettingsHost(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    store: SettingsStore = koinInject(),
    folders: GrantedFolders = koinInject(),
    repository: TrimRepository = koinInject(),
    nightPass: NightPass = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val colors = TrimTheme.colors

    // Null until the store's first emission. Rendering `Settings()` in the meantime would
    // show the *defaults* for a second — Standard quality, a 60-minute cap — which is a lie
    // to anybody who has changed them, and a lie that invites a tap on the value they
    // already chose.
    val settings by store.settings.collectAsState(initial = null)

    var rows by remember { mutableStateOf(emptyList<FolderRow>()) }
    // Bumped whenever something changed underneath the list: a grant taken, a mode saved.
    var revision by remember { mutableStateOf(0) }
    val picker = rememberFolderPicker(folders, nightPass) { revision++ }

    LaunchedEffect(revision) { rows = folderRows(folders, repository) }

    Box(modifier.fillMaxSize().background(colors.page)) {
        settings?.let { current ->
            SettingsScreen(
                settings = current,
                folders = rows,
                onQualityTarget = { target -> scope.launch { store.update { it.copy(qualityTarget = target) } } },
                onStartWhenFull = { on -> scope.launch { store.update { it.copy(startWhenFull = on) } } },
                onNightlyCap = { minutes -> scope.launch { store.update { it.copy(nightlyCapMinutes = minutes) } } },
                onFolderMode = { row, mode ->
                    scope.launch {
                        repository.saveFolderGrant(row.toGrant(mode, folders))
                        revision++
                    }
                },
                onAddFolder = { picker.choose() },
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
                header = { BackToPhotos(onBack) },
                // The two things here that are Android's rather than the app's, so they
                // are passed in rather than written into the shared screen.
                footer = {
                    Column(verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp)) {
                        DiagnosticsButton()
                        About()
                    }
                },
            )
        }
        picker.HelpSheet()
    }
}

/**
 * Which build this is.
 *
 * Always shown, unlike the diagnostics button beside it: the button appears only when
 * there is a crash to send, and the question this answers — "am I even on the build with
 * the fix?" — is asked most often when nothing has crashed at all.
 */
@Composable
private fun About() {
    BasicText(
        text = BuildIdentity.line,
        style = TrimTheme.typography.caption.copy(color = TrimTheme.colors.muted),
    )
}

@Composable
private fun BackToPhotos(onBack: () -> Unit) {
    BasicText(
        text = "← Photos",
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
