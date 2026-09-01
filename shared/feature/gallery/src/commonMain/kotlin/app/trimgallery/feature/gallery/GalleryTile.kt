package app.trimgallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.ui.motion.HeroGeometry
import app.trimgallery.core.ui.motion.MotionSpec
import app.trimgallery.core.ui.motion.ProgressRing
import app.trimgallery.core.ui.motion.breathing
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * One square thumbnail in the grid.
 *
 * The artwork is a slot rather than an image loader call: this module then depends on
 * neither Coil nor a platform decoder, the Android host supplies the real thumbnail
 * pipeline, and a test or preview can pass a solid colour. It also keeps
 * every `shared/feature` module honest about the one-way dependency flow in
 * ARCHITECTURE.md § 2.
 *
 * @param onBounds reports the tile's rectangle in window coordinates **in dp**, which is
 *   where the shared-element transition to the viewer starts from. Dp rather than the
 *   pixels `boundsInWindow` returns, and typed as [HeroGeometry.Rect] rather than a
 *   Compose `Rect` so the unit is part of the signature: passing the pixel values through
 *   unconverted made the opened image three times too large on a 3x phone, and nothing
 *   caught it — the geometry tests build their rectangles in dp directly and never go
 *   through this composable.
 */
@Composable
fun GalleryTile(
    item: MediaItem,
    processing: Boolean,
    onOpen: (MediaItem) -> Unit,
    onBounds: (HeroGeometry.Rect) -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    previewing: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    preview: @Composable (MediaItem) -> Unit = {},
    artwork: @Composable (MediaItem) -> Unit,
) {
    val colors = TrimTheme.colors
    val density = LocalDensity.current
    val radius = MotionSpec.Hero.TILE_RADIUS_DP.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onGloballyPositioned { coordinates ->
                val px = coordinates.boundsInWindow()
                with(density) {
                    onBounds(
                        HeroGeometry.Rect(
                            left = px.left.toDp().value,
                            top = px.top.toDp().value,
                            width = px.width.toDp().value,
                            height = px.height.toDp().value,
                        ),
                    )
                }
            }
            .breathing(
                id = item.platformRef.value,
                active = processing,
                accent = colors.accent,
                glowAlpha = colors.glowAlpha,
                cornerRadius = radius,
            )
            .clip(RoundedCornerShape(radius))
            .background(colors.card)
            .pressScale { onOpen(item) },
    ) {
        // The still stays mounted underneath the preview. Swapping it out would leave a
        // blank tile for however long the player takes to render its first frame, and the
        // whole point of a dwell preview is that it arrives without the grid flinching.
        artwork(item)
        if (previewing) preview(item)
        if (processing) {
            ProgressRing(
                progress = progress,
                color = colors.accent,
                reduceMotion = TrimTheme.reduceMotion,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize()
                    .padding(MotionSpec.ProgressRing.INSET_DP.dp),
            )
        }
        overlay()
    }
}

/** The circular badge marking a video, filled with the accent while it is playing. */
@Composable
fun BoxScope.ClipBadge(playing: Boolean, content: @Composable () -> Unit) {
    val colors = TrimTheme.colors
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(10.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(if (playing) colors.accent else colors.card.copy(alpha = CHIP_ALPHA)),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** A frosted pill for durations and counts over artwork. */
@Composable
fun TileChip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = TrimTheme.colors
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.card.copy(alpha = CHIP_ALPHA))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        content = { content() },
    )
}

/** Enough to read a label over any photograph without hiding it. */
private const val CHIP_ALPHA = 0.85f

/** Fills the tile with a flat colour. Used by previews and tests in place of a decoder. */
@Composable
fun PlaceholderArtwork(@Suppress("UNUSED_PARAMETER") item: MediaItem) {
    Box(Modifier.fillMaxSize().background(TrimTheme.colors.band))
}
