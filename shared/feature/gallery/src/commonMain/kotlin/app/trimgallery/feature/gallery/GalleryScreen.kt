package app.trimgallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.ui.grid.DateSections
import app.trimgallery.core.ui.grid.FastScroll
import app.trimgallery.core.ui.grid.GridZoom
import app.trimgallery.core.ui.motion.HeroGeometry
import app.trimgallery.core.ui.motion.arrival
import app.trimgallery.core.ui.theme.TrimTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The gallery grid (BUILD.md § 13, milestone 8).
 *
 * Sticky date headers, pinch between day/month/year densities, a fast-scroll date bar,
 * and the shared-element transition into the viewer.
 *
 * All the arithmetic — sectioning, header wording, zoom stepping, scrubber mapping —
 * lives in `shared/core/ui/grid` and is unit tested. This composable arranges it.
 *
 * @param items newest first. Sorting here would hide a bug in the query rather than fix
 *   it, and the query is the only place that knows how the user asked to sort.
 * @param processingIds items the night pass is working on; those tiles breathe.
 * @param artwork supplies thumbnails, injected so this module depends on no image loader.
 */
@Composable
fun GalleryScreen(
    items: List<MediaItem>,
    processingIds: Set<String>,
    today: LocalDate,
    timeZone: TimeZone,
    modifier: Modifier = Modifier,
    initialZoom: GridZoom = GridZoom.Default,
    emptyState: @Composable () -> Unit = { GalleryEmptyState() },
    sheet: @Composable (MediaItem) -> Unit = {},
    tileOverlay: @Composable BoxScope.(MediaItem) -> Unit = {},
    /**
     * Plays a video item in the viewer. A slot for the same reason [artwork] is one: this
     * module depends on no player, and the Android host supplies ExoPlayer. Items whose
     * kind is not video never reach it.
     */
    video: @Composable (MediaItem) -> Unit = {},
    /**
     * A muted, silent preview drawn over a video tile the grid has settled on
     * (BUILD.md § 9, "video tiles autoplay muted on dwell"). A slot for the same reason
     * [artwork] and [video] are: this module names no player.
     */
    preview: @Composable (MediaItem) -> Unit = {},
    /** Per-item progress for the ring, when the pipeline can say. Null means "busy". */
    progressOf: (MediaItem) -> Float? = { null },
    artwork: @Composable (MediaItem) -> Unit,
) {
    val colors = TrimTheme.colors
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var zoom by remember { mutableStateOf(initialZoom) }
    var open by remember { mutableStateOf<MediaItem?>(null) }

    // Live tile rectangles, so closing the viewer returns the image to where the tile is
    // *now* rather than where it was when it opened.
    val bounds = remember { mutableStateMapOf<String, HeroGeometry.Rect>() }

    val sections = remember(items, zoom, timeZone, today) {
        DateSections.sections(items, zoom, timeZone, today) { it.takenAt }
    }

    // The scrubber only appears while the grid is moving (BUILD.md § 9: chrome fades
    // when idle). A permanent bar over the photographs would be the opposite.
    val scrolling by remember { derivedStateOf { gridState.isScrollInProgress } }

    // Which video tile previews: the one nearest the middle of the viewport, and only once
    // the grid has been still for a moment.
    //
    // Nearest-the-middle rather than first-visible because the eye is in the middle of the
    // screen, and first-visible is usually the row half cut off by the top edge. One at a
    // time because each preview is a decoder; a grid that starts a player per visible video
    // would hold six on a screen that shows six.
    //
    // `collectLatest` is doing the real work: any scroll cancels the pending delay, so the
    // preview only ever starts after the grid has actually settled rather than flickering
    // through every tile that passes the middle during a fling.
    val videoIds = remember(items) { items.filter { it.kind == MediaKind.VIDEO }.map { it.id }.toSet() }
    var previewId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gridState, items) {
        snapshotFlow { gridState.isScrollInProgress to centreVideoKey(gridState, videoIds) }
            .collectLatest { (moving, candidate) ->
                if (moving || candidate == null) {
                    previewId = null
                } else {
                    delay(DWELL_MS)
                    previewId = candidate
                }
            }
    }
    val thumbFraction by remember {
        derivedStateOf { FastScroll.fractionOf(sections, gridState.firstVisibleItemIndex) }
    }

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize().background(colors.page), contentAlignment = Alignment.Center) {
            emptyState()
        }
        return
    }

    Box(modifier.fillMaxSize().background(colors.page)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(zoom.columns),
            state = gridState,
            contentPadding = PaddingValues(GRID_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(zoom) {
                    // Pinch changes density. GridZoom.step applies the deadband, so an
                    // incidental two-finger scroll does not flip the grid.
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        if (gestureZoom != 1f) zoom = GridZoom.step(zoom, gestureZoom)
                    }
                },
        ) {
            var flatIndex = 0
            sections.forEach { section ->
                item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(section.label)
                }
                section.items.forEach { mediaItem ->
                    val index = flatIndex++
                    item(key = mediaItem.id) {
                        GalleryTile(
                            item = mediaItem,
                            processing = mediaItem.id in processingIds,
                            progress = progressOf(mediaItem),
                            previewing = previewId == mediaItem.id && mediaItem.kind == MediaKind.VIDEO,
                            preview = preview,
                            onOpen = { open = it },
                            onBounds = { rect -> bounds[mediaItem.id] = rect },
                            modifier = Modifier
                                .arrival(index = index, key = zoom)
                                // Read inside the layer block so opening a tile does not
                                // recompose the grid.
                                .graphicsLayer { alpha = if (open?.id == mediaItem.id) 0f else 1f },
                            overlay = { tileOverlay(mediaItem) },
                            artwork = artwork,
                        )
                    }
                }
            }
        }

        FastScrollBar(
            sections = sections,
            today = today,
            thumbFraction = thumbFraction,
            visible = scrolling,
            onScrubTo = { index ->
                // +1 per preceding header, since headers occupy a slot in the grid too.
                val section = FastScroll.sectionOf(sections, index)
                scope.launch { gridState.scrollToItem(index + section + 1) }
            },
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        open?.let { item ->
            // The grid's own order, flattened. The pager has to walk the library in the
            // order the user is looking at, not the order the query returned, or a swipe
            // right lands somewhere they have never seen.
            val ordered = remember(sections) { sections.flatMap { it.items } }
            val startIndex = remember(ordered, item.id) { ordered.indexOfFirst { it.id == item.id } }
            HeroViewer(
                items = ordered,
                startIndex = startIndex,
                // A tile that has not reported its bounds yet opens from a point rather
                // than from a rectangle: `target(0f, 0f)` used to be the fallback and
                // returned a *negative* rectangle, which `Modifier.size` rejects — the
                // crash behind "tapping a photo closes the app".
                tileBounds = { shown -> bounds[shown.id] ?: HeroGeometry.Rect(0f, 0f, 0f, 0f) },
                onClose = { open = null },
                sheet = sheet,
                video = video,
                artwork = artwork,
            )
        }
    }
}

/**
 * The key of the visible video tile nearest the middle of the viewport, or null.
 *
 * Distance is measured between centres rather than to the top edge, so a tall row half off
 * the bottom does not beat one fully on screen. Headers span the row and are not items, so
 * they are skipped by [videoIds] rather than by parsing their key.
 */
private fun centreVideoKey(state: LazyGridState, videoIds: Set<String>): String? {
    if (videoIds.isEmpty()) return null
    val layout = state.layoutInfo
    val middle = layout.viewportSize.height / 2
    return layout.visibleItemsInfo
        .asSequence()
        .filter { it.key is String && it.key in videoIds }
        .minByOrNull { kotlin.math.abs((it.offset.y + it.size.height / 2) - middle) }
        ?.key as? String
}

/**
 * How long the grid must be still before a preview starts.
 *
 * Long enough that a flick through the library does not start and stop a player for every
 * video that crosses the middle; short enough that stopping to look at something feels
 * like it responded rather than lagged.
 */
private const val DWELL_MS = 400L

@Composable
private fun SectionHeader(label: String) {
    BasicText(
        text = label,
        style = TrimTheme.typography.label.copy(color = TrimTheme.colors.text),
        modifier = Modifier.padding(top = HEADER_TOP_DP.dp, bottom = HEADER_BOTTOM_DP.dp),
    )
}

/**
 * Shown when there is nothing to show.
 *
 * An empty screen is an invitation to act, so it says what to do rather than only that
 * the library is empty.
 */
@Composable
fun GalleryEmptyState() {
    val colors = TrimTheme.colors
    Box(Modifier.padding(GRID_PADDING_DP.dp)) {
        BasicText(
            text = "No photos yet.\nGrant a folder in Settings and they will appear here.",
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )
    }
}

private const val GRID_GAP_DP = 3
private const val GRID_PADDING_DP = 3
private const val HEADER_TOP_DP = 20
private const val HEADER_BOTTOM_DP = 8
