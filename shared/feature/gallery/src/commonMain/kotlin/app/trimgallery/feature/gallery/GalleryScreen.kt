package app.trimgallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.ui.motion.HeroGeometry
import app.trimgallery.core.ui.motion.arrival
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * The gallery shell (BUILD.md § 13, milestone 8): a square grid that opens into a
 * viewer with a shared-element transition.
 *
 * Covered here: the grid, the viewer, and the whole motion spec ported from the
 * signed-off reference. Still to come in this milestone: albums, favourites, the trash
 * (= undo bin), the locked folder, and the fast-scroll date bar.
 *
 * @param processingIds items the night pass is currently working on; those tiles breathe.
 * @param artwork supplies thumbnails. Injected so this module depends on no image loader
 *   and no platform decoder — the Android host wires the real pipeline, a preview passes
 *   a flat colour.
 */
@Composable
fun GalleryScreen(
    items: List<MediaItem>,
    processingIds: Set<Long>,
    modifier: Modifier = Modifier,
    gridKey: Any = Unit,
    sheet: @Composable (MediaItem) -> Unit = {},
    tileOverlay: @Composable BoxScope.(MediaItem) -> Unit = {},
    artwork: @Composable (MediaItem) -> Unit,
) {
    val colors = TrimTheme.colors
    val gridState = rememberLazyGridState()

    var open by remember { mutableStateOf<MediaItem?>(null) }

    // Live tile rectangles, so closing returns the image to where the tile is *now*
    // rather than where it was when the viewer opened.
    val bounds = remember { mutableStateMapOf<Long, HeroGeometry.Rect>() }

    Box(modifier.fillMaxSize().background(colors.page)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = gridState,
            contentPadding = PaddingValues(GRID_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                GalleryTile(
                    item = item,
                    index = index,
                    processing = item.id in processingIds,
                    onOpen = { open = it },
                    onBounds = { rect ->
                        bounds[item.id] = HeroGeometry.Rect(rect.left, rect.top, rect.width, rect.height)
                    },
                    // The open item's tile is left in place but transparent: the viewer
                    // draws it, and the tile's rectangle must stay measurable for the
                    // return journey.
                    modifier = Modifier
                        .arrival(index = index, key = gridKey)
                        // Read inside the layer block so toggling it skips recomposition.
                        .graphicsLayer { alpha = if (open?.id == item.id) 0f else 1f },
                    overlay = { tileOverlay(item) },
                    artwork = artwork,
                )
            }
        }

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

private const val GRID_COLUMNS = 2
private const val GRID_GAP_DP = 14
private const val GRID_PADDING_DP = 18
