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
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.feature.gallery.GalleryScreen
import kotlinx.coroutines.flow.catch
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
@Composable
fun GalleryHost(
    modifier: Modifier = Modifier,
    storage: LibraryStorage = koinInject(),
    folders: GrantedFolders = koinInject(),
) {
    var grants by remember { mutableStateOf(folders.grants()) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var scanning by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            folders.take(uri)
            grants = folders.grants()
        }
    }

    LaunchedEffect(grants) {
        if (grants.isEmpty()) {
            items = emptyList()
            return@LaunchedEffect
        }
        scanning = true
        failure = null
        val found = mutableListOf<MediaItem>()
        storage.scan(grants)
            .catch { failure = it.message ?: it::class.simpleName }
            .collect { item ->
                found += item
                // Published in batches while the walk continues. A granted DCIM folder can
                // hold tens of thousands of files and `SafStorage.scan` is a Flow precisely
                // so the caller need not wait for the end of it; re-sorting on every single
                // arrival would make the scan quadratic and the UI stutter.
                if (found.size % SCAN_BATCH == 0) items = found.newestFirst()
            }
        items = found.newestFirst()
        scanning = false
    }

    when {
        grants.isEmpty() -> NoFolderYet(modifier) { picker.launch(null) }
        else -> GalleryScreen(
            items = items,
            processingIds = emptySet(),
            today = Clock.System.todayIn(TimeZone.currentSystemDefault()),
            timeZone = TimeZone.currentSystemDefault(),
            modifier = modifier,
            emptyState = { ScanState(scanning = scanning, failure = failure) { picker.launch(null) } },
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
private fun List<MediaItem>.newestFirst(): List<MediaItem> = map { item ->
    if (item.takenAt != null) item else item.copy(takenAt = Instant.fromEpochMilliseconds(item.mtime))
}.sortedByDescending { it.takenAt }

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
    }
}

/** How many items to publish at a time while a scan is still walking. */
private const val SCAN_BATCH = 200
private const val BUTTON_V_DP = 12
