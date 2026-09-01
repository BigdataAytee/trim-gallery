package app.trimgallery.feature.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.ui.motion.HeroGeometry
import app.trimgallery.core.ui.motion.MotionSpec
import app.trimgallery.core.ui.motion.toCompose
import app.trimgallery.core.ui.theme.TrimTheme
import kotlinx.coroutines.launch

/**
 * The grid → viewer shared-element transition (BUILD.md § 9).
 *
 * The tile's own rectangle is the starting frame and [HeroGeometry.target] is the
 * destination; a single 0..1 progress drives both, so the image travels as one shape
 * instead of four independently animated edges. Closing runs the same journey backwards
 * into whatever rectangle the tile now occupies, which is why [tileBounds] is read at
 * dismissal rather than captured at open — the grid may have scrolled underneath.
 *
 * Drag-down dismissal is a spring, per BUILD.md § 9 ("drag-down shrinks it back into
 * place", "spring physics on swipes and dismissals").
 */
@Composable
fun HeroViewer(
    items: List<MediaItem>,
    startIndex: Int,
    tileBounds: (MediaItem) -> HeroGeometry.Rect,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sheet: @Composable (MediaItem) -> Unit = {},
    video: @Composable (MediaItem) -> Unit = {},
    artwork: @Composable (MediaItem) -> Unit,
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(items.indices)) { items.size }
    val item = items[pager.currentPage.coerceIn(items.indices)]
    val colors = TrimTheme.colors
    val reduce = TrimTheme.reduceMotion
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val windowWidth = maxWidth.value
        val windowHeight = maxHeight.value
        val target = remember(windowWidth, windowHeight) {
            HeroGeometry.target(windowWidth, windowHeight)
        }

        // 0 = sitting in the grid, 1 = fully open.
        val progress = remember { Animatable(0f) }
        var dragDp by remember { mutableStateOf(0f) }
        var closing by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (reduce) {
                progress.snapTo(1f)
            } else {
                progress.animateTo(
                    1f,
                    tween(MotionSpec.Hero.OPEN_MS, easing = MotionSpec.Hero.OPEN_EASING.toCompose()),
                )
            }
        }

        suspend fun dismiss() {
            if (closing) return
            closing = true
            if (reduce) {
                progress.snapTo(0f)
            } else {
                progress.animateTo(
                    0f,
                    tween(MotionSpec.Hero.CLOSE_MS, easing = MotionSpec.Hero.CLOSE_EASING.toCompose()),
                )
            }
            onClose()
        }

        val dismissProgress = HeroGeometry.dismissProgress(dragDp, windowHeight)

        // The backdrop thins as the image is dragged away, so the grid reappears
        // progressively rather than all at once on release.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value * (1f - dismissProgress) }
                .background(colors.scrim)
                .pointerInput(Unit) { detectTapGestures { scope.launch { dismiss() } } },
        )

        val frame = HeroGeometry.lerp(tileBounds(item), target, progress.value)
        val radius = HeroGeometry.lerpRadius(progress.value)

        Box(
            Modifier
                .offset {
                    IntOffset(frame.left.dp.roundToPx(), (frame.top + dragDp).dp.roundToPx())
                }
                // Clamped as well as fixed at the source. `Modifier.size` throws on a
                // negative dimension, and a viewer that crashes is worse than a viewer
                // that briefly shows nothing.
                .size(frame.width.coerceAtLeast(0f).dp, frame.height.coerceAtLeast(0f).dp)
                .graphicsLayer {
                    val s = HeroGeometry.dismissScale(dismissProgress)
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(radius.dp))
                .background(colors.card)
                .pointerInput(item.id) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (HeroGeometry.dismissProgress(dragDp, windowHeight) >
                                    HeroGeometry.DISMISS_THRESHOLD
                                ) {
                                    dismiss()
                                } else {
                                    // Springs back into place rather than snapping.
                                    val settle = Animatable(dragDp)
                                    settle.animateTo(
                                        0f,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                    ) { dragDp = value }
                                }
                            }
                        },
                        onVerticalDrag = { _, delta -> dragDp += with(density) { delta.toDp().value } },
                    )
                },
        ) {
            // Horizontal paging inside the hero frame, so swiping between items keeps the
            // shape the shared element is animating and the close transition still lands
            // on a real tile — the one for whatever is on screen now, not the one tapped.
            //
            // `beyondViewportPageCount = 0`: neighbours are not composed ahead of time.
            // Every page is a decode, and on a video page it is a player; pre-warming two
            // of those either side would have three video pipelines alive to show one.
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 0,
                // Paging is off until the viewer has arrived. During the open animation the
                // frame is still travelling, and a horizontal drag then fights the
                // transition for the same gesture.
                userScrollEnabled = progress.value >= 1f,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageItem = items[page]
                Box(Modifier.fillMaxSize()) {
                    // Only the page in front plays. A paused player on a neighbouring page
                    // still holds a decoder, and BUILD.md § 2 rule 2's sibling concern —
                    // that the foreground wins the hardware — applies to playback too.
                    if (pageItem.kind == MediaKind.VIDEO && page == pager.currentPage) {
                        video(pageItem)
                    } else {
                        artwork(pageItem)
                    }
                }
            }
        }

        // Follows the image up, starting once the zoom has visibly begun.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value * (1f - dismissProgress) },
        ) {
            sheet(item)
        }
    }
}
