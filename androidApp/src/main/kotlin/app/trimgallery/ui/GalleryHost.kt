package app.trimgallery.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.android.FolderChoice
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.NightPass
import app.trimgallery.feature.gallery.GalleryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the launcher icon opens: the gallery, over whatever folders the user has granted.
 *
 * Until this existed the Activity drew the app's name on an empty page, and the
 * instrumented launch test passed against that — reaching RESUMED says the process came
 * up, not that anything is on screen. Every screen under `shared/feature` was written and
 * unit tested and none of them had ever been mounted.
 *
 * This is the smallest honest host: grant a folder, walk it, show what is in it. No
 * database, no night pass, no navigation — those arrive with the screens that need them.
 */
@UnstableApi
@Composable
fun GalleryHost(
    modifier: Modifier = Modifier,
    storage: LibraryStorage = koinInject(),
    folders: GrantedFolders = koinInject(),
    nightPass: NightPass = koinInject(),
) {
    var grants by remember { mutableStateOf(folders.grants()) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var scanning by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Null means one of two things the app cannot tell apart: Android refused the folder
    // (the picker greys out "Use this folder" for the three locations in FolderChoice, so
    // the only way out is to back away), or the user changed their mind. Either way the
    // next screen has to be help rather than an accusation.
    var help by remember { mutableStateOf<HelpState>(HelpState.Hidden) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        when {
            uri == null -> help = HelpState.Shown(refusal = null)
            else -> {
                val refusal = FolderChoice.refusalFor(uri)
                if (refusal != null) {
                    // Reachable when a picker allows what the platform documents as
                    // unpickable. Taking the grant would leave a folder that is stored,
                    // looks granted, and scans nothing.
                    help = HelpState.Shown(refusal)
                } else {
                    folders.take(uri)
                    grants = folders.grants()
                    help = HelpState.Hidden
                    // The first moment the app has anything to work on. Until this call
                    // existed, granting a folder scheduled nothing: NightScheduler.schedule
                    // had no caller anywhere in the app, so the night pass had never run on
                    // any device.
                    nightPass.sync()
                }
            }
        }
    }

    // Opening at DCIM/Camera rather than wherever the picker last was. It is a hint, not a
    // guarantee — a device without that folder opens where it likes — but it means the
    // ordinary path never meets a blocked folder in the first place.
    fun choose() = picker.launch(FolderChoice.cameraFolderHint())

    LaunchedEffect(grants) {
        if (grants.isEmpty()) {
            items = emptyList()
            return@LaunchedEffect
        }
        scanning = true
        failure = null
        val found = mutableListOf<MediaItem>()
        var nextPublish = FIRST_BATCH
        storage.scan(grants)
            .catch { failure = it.message ?: it::class.simpleName }
            .collect { item ->
                found += item
                // Published while the walk continues, at a doubling interval. `SafStorage.scan`
                // is a Flow precisely so the caller need not wait for the end of a folder that
                // can hold tens of thousands of files — but each publish sorts everything found
                // so far, so publishing at a fixed interval would sort the whole list N/interval
                // times and get slower exactly as the library gets bigger. Doubling makes that a
                // handful of sorts however large the folder is, while keeping early feedback
                // fast: 200 items, then 400, 800, and so on to a ceiling so updates never stop.
                if (found.size >= nextPublish) {
                    items = found.sortedNewestFirst()
                    nextPublish = (nextPublish * 2).coerceAtMost(found.size + MAX_BATCH)
                }
            }
        items = found.sortedNewestFirst()
        scanning = false
    }

    (help as? HelpState.Shown)?.let { shown ->
        FolderHelpSheet(
            refusal = shown.refusal,
            onOpenCamera = {
                help = HelpState.Hidden
                choose()
            },
            onPickAnother = {
                help = HelpState.Hidden
                // Deliberately no hint here: this is the escape hatch for someone whose
                // photos are not in DCIM/Camera, and reopening at Camera every time would
                // make it the button that does not do what it says.
                picker.launch(null)
            },
        )
    }

    when {
        grants.isEmpty() -> NoFolderYet(modifier) { choose() }
        else -> GalleryScreen(
            items = items,
            processingIds = emptySet(),
            today = Clock.System.todayIn(TimeZone.currentSystemDefault()),
            timeZone = TimeZone.currentSystemDefault(),
            modifier = modifier,
            emptyState = { ScanState(scanning = scanning, failure = failure) { choose() } },
            video = { VideoPlayer(it, modifier = Modifier.fillMaxSize()) },
            preview = { TilePreview(it, modifier = Modifier.fillMaxSize()) },
            artwork = { Thumbnail(it) },
        )
    }
}

/**
 * Newest first, which is the order `GalleryScreen` and `DateSections` both require.
 *
 * Sorted on `takenAt ?: mtime`, and the fallback is the point. `SafStorage.scan` reads one
 * cursor per folder — name, size, mime, mtime — and nothing else: the date a photo was
 * taken lives in its EXIF, `ContainerReaderAndroid` reads that, and it does so only for
 * the handful of files the library diff found to be new or changed, because a header read
 * per file turns a second into a minute on a large library.
 *
 * So on a first run every item is undated, and every one of them would land in a single
 * "Undated" section. The file's modification time is an approximation — a photo copied
 * from another device carries the copy's date, not the shot's — but an approximately
 * ordered grid is a great deal more useful than one undifferentiated block, and the real
 * date replaces it as soon as the item is indexed. Recorded in PROJECT.md.
 */
private suspend fun List<MediaItem>.sortedNewestFirst(): List<MediaItem> =
    // Off the main thread. This runs inside the collector, which is resumed on the main
    // dispatcher because that is where Compose state has to be written — and a copy plus a
    // sort of a hundred thousand items there is a visibly frozen frame, on exactly the
    // libraries this app exists for. The list cannot change underneath it: the collector is
    // suspended for the duration, so nothing is appending while this runs.
    withContext(Dispatchers.Default) {
        map { item ->
            if (item.takenAt != null) item else item.copy(takenAt = Instant.fromEpochMilliseconds(item.mtime))
        }.sortedByDescending { it.takenAt }
    }

@Composable
private fun NoFolderYet(modifier: Modifier = Modifier, onChoose: () -> Unit) {
    val colors = TrimTheme.colors
    Box(
        modifier = modifier.fillMaxSize().background(colors.page),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
            modifier = Modifier.padding(TrimSpacing.INSET_DP.dp),
        ) {
            BasicText(
                text = "Trim Gallery",
                style = TrimTheme.typography.title.copy(color = colors.text, textAlign = TextAlign.Center),
            )
            BasicText(
                text = "Choose the folder your photos are in. Nothing is read until you do, " +
                    "and nothing ever leaves your phone.",
                style = TrimTheme.typography.body.copy(color = colors.muted, textAlign = TextAlign.Center),
            )
            Box(
                modifier = Modifier
                    .pressScale(onChoose)
                    .background(colors.accent, RoundedCornerShape(TrimShape.BUTTON_DP.dp))
                    .padding(horizontal = TrimSpacing.CARD_PADDING_DP.dp, vertical = BUTTON_V_DP.dp),
            ) {
                BasicText(
                    text = "Choose folder",
                    style = TrimTheme.typography.label.copy(color = colors.accentOn),
                )
            }
            // Only renders when a crash has been recorded. On the first-run screen because
            // a crash loop may never reach the grid, and the report has to be reachable
            // from wherever the app can still get to.
            DiagnosticsButton()
        }
    }
}

/**
 * What the grid shows while it has nothing to show: scanning, or a scan that failed, or a
 * granted folder that genuinely holds no media.
 */
@Composable
private fun ScanState(scanning: Boolean, failure: String?, onChoose: () -> Unit) {
    val colors = TrimTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
        modifier = Modifier.padding(TrimSpacing.INSET_DP.dp),
    ) {
        BasicText(
            text = when {
                failure != null -> "That folder could not be read: $failure"
                scanning -> "Looking through your folder…"
                else -> "Nothing here that this app can work with yet."
            },
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )
        if (!scanning) {
            Box(modifier = Modifier.pressScale(onChoose)) {
                BasicText(
                    text = "Choose another folder",
                    style = TrimTheme.typography.label.copy(color = colors.accent),
                )
            }
        }
        DiagnosticsButton()
    }
}

/** Whether the folder-help sheet is up, and what it should lead with. */
private sealed interface HelpState {
    data object Hidden : HelpState
    data class Shown(val refusal: FolderChoice.Refusal?) : HelpState
}

/** How many items to find before the grid first fills in, while a scan is still walking. */
private const val FIRST_BATCH = 200

/** The ceiling on the doubling interval, so a very large folder keeps showing progress. */
private const val MAX_BATCH = 5_000
private const val BUTTON_V_DP = 12
