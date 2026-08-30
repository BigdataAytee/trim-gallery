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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.ui.grid.DateSections
import app.trimgallery.core.ui.grid.FastScroll
import app.trimgallery.core.ui.grid.GridZoom
import app.trimgallery.core.ui.motion.HeroGeometry
import app.trimgallery.core.ui.motion.arrival
import app.trimgallery.core.ui.theme.TrimTheme
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
    processingIds: Set<Long>,
    today: LocalDate,
    timeZone: TimeZone,
    modifier: Modifier = Modifier,
    initialZoom: GridZoom = GridZoom.Default,
    emptyState: @Composable () -> Unit = { GalleryEmptyState() },
    sheet: @Composable (MediaItem) -> Unit = {},
    tileOverlay: @Composable BoxScope.(MediaItem) -> Unit = {},
    artwork: @Composable (MediaItem) -> Unit,
) {
    val colors = TrimTheme.colors
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var zoom by remember { mutableStateOf(initialZoom) }
    var open by remember { mutableStateOf<MediaItem?>(null) }

    // Live tile rectangles, so closing the viewer returns the image to where the tile is
    // *now* rather than where it was when it opened.
    val bounds = remember { mutableStateMapOf<Long, HeroGeometry.Rect>() }

    val sections = remember(items, zoom, timeZone, today) {
        DateSections.sections(items, zoom, timeZone, today) { it.takenAt }
    }

    // The scrubber only appears while the grid is moving (BUILD.md § 9: chrome fades
    // when idle). A permanent bar over the photographs would be the opposite.
    val scrolling by remember { derivedStateOf { gridState.isScrollInProgress } }
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
                            onOpen = { open = it },
                            onBounds = { rect ->
                                bounds[mediaItem.id] =
                                    HeroGeometry.Rect(rect.left, rect.top, rect.width, rect.height)
                            },
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
            HeroViewer(
                item = item,
                tileBounds = { bounds[item.id] ?: HeroGeometry.target(0f, 0f) },
                onClose = { open = null },
                sheet = { sheet(item) },
                artwork = artwork,
            )
        }
    }
}

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
