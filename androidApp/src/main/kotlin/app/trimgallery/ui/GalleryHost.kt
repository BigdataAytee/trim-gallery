package app.trimgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
 * This is the smallest honest host: grant a folder, walk it, show what is in it. Settings
 * is a sibling rather than a child — `TrimApp` owns which of the two is on screen — so
 * this file still has no navigation of its own.
 */
@UnstableApi
@Composable
fun GalleryHost(
    modifier: Modifier = Modifier,
    /**
     * Controls drawn over the grid and under the viewer — the way in to Settings.
     *
     * Passed down rather than stacked on top of this host, because a control drawn above
     * everything would float over an opened photograph. `GalleryScreen` owns the order.
     */
    chrome: @Composable BoxScope.() -> Unit = {},
    storage: LibraryStorage = koinInject(),
    folders: GrantedFolders = koinInject(),
    nightPass: NightPass = koinInject(),
) {
    var grants by remember { mutableStateOf(folders.grants()) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var scanning by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val picker = rememberFolderPicker(folders, nightPass) { grants = it }

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

    // A Box, and the sheet last, because Compose draws siblings in the order they are
    // emitted: the help sheet used to be emitted before the grid, which meant the grid's
    // opaque background was painted straight over it and the sheet was never seen.
    Box(modifier.fillMaxSize()) {
        when {
            grants.isEmpty() -> {
                NoFolderYet { picker.choose() }
                chrome()
            }
            else -> GalleryScreen(
                items = items,
                processingIds = emptySet(),
                today = Clock.System.todayIn(TimeZone.currentSystemDefault()),
                timeZone = TimeZone.currentSystemDefault(),
                emptyState = { ScanState(scanning = scanning, failure = failure) { picker.choose() } },
                video = { VideoPlayer(it, modifier = Modifier.fillMaxSize()) },
                preview = { TilePreview(it, modifier = Modifier.fillMaxSize()) },
                chrome = chrome,
                artwork = { Thumbnail(it) },
            )
        }
        picker.HelpSheet()
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
private fun NoFolderYet(onChoose: () -> Unit) {
    val colors = TrimTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(colors.page),
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

/** How many items to find before the grid first fills in, while a scan is still walking. */
private const val FIRST_BATCH = 200

/** The ceiling on the doubling interval, so a very large folder keeps showing progress. */
private const val MAX_BATCH = 5_000
private const val BUTTON_V_DP = 12
