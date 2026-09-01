package app.trimgallery.core.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.trimgallery.core.ui.theme.LocalReduceMotion
import app.trimgallery.core.ui.theme.TrimSpring

/** [MotionSpec.Easing] as a Compose easing curve. */
fun MotionSpec.Easing.toCompose(): Easing = CubicBezierEasing(x1, y1, x2, y2)

/**
 * The design system's spring as a Compose one.
 *
 * DESIGN_SYSTEM.md gives stiffness and damping; Compose wants stiffness and a damping
 * *ratio*, which `TrimSpring.dampingRatio` already computes. Without this the tokens are
 * a table nobody can use from a composable, which is how `HeroViewer` ended up asking for
 * `DampingRatioMediumBouncy` — a Compose default that is close to `STANDARD` and is not
 * it, so the dismissal settled to a rhythm no token describes.
 */
fun <T> TrimSpring.toCompose(): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)

/**
 * The slow pulse marking a thumbnail as being worked on (BUILD.md § 9: "thin progress
 * ring on thumbnails being processed").
 *
 * @param id the item's stable id, which fixes this tile's offset into the cycle so
 *   neighbours never pulse together.
 * @param active false for tiles that are not currently queued or processing.
 */
fun Modifier.breathing(id: String, active: Boolean, accent: Color, glowAlpha: Float, cornerRadius: Dp): Modifier =
    composed {
        val reduce = LocalReduceMotion.current

        if (!active) return@composed this

        if (reduce) {
            // A static ring says the same thing without the movement (DESIGN_SPEC § 4.6).
            return@composed this.border(
                width = MotionSpec.Breathing.STATIC_RING_DP.dp,
                color = accent.copy(alpha = MotionSpec.Breathing.STATIC_RING_ALPHA),
                shape = RoundedCornerShape(cornerRadius),
            )
        }

        val transition = rememberInfiniteTransition(label = "breathing")
        val pulse by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(MotionSpec.Breathing.PERIOD_MS / 2, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(TilePhase.offsetMs(id)),
            ),
            label = "pulse",
        )

        this
            .drawBehind {
                // The halo sits outside the tile, so it is drawn behind rather than clipped.
                val halo = MotionSpec.Breathing.HALO_DP.dp.toPx() * pulse
                if (halo <= 0f) return@drawBehind
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = glowAlpha * pulse * HALO_STRENGTH), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension / 2f + halo,
                    ),
                    radius = size.minDimension / 2f + halo,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
            .border(
                width = MotionSpec.Breathing.RING_DP.dp * pulse,
                color = accent.copy(alpha = glowAlpha * pulse),
                shape = RoundedCornerShape(cornerRadius),
            )
    }

/**
 * Tiles fade, rise and settle as they arrive.
 *
 * Runs once per tile per grid population; changing [key] (the album or tab) replays it.
 */
fun Modifier.arrival(index: Int, key: Any): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val progress = remember(key) { Animatable(if (reduce) 1f else 0f) }

    LaunchedEffect(key, reduce) {
        if (reduce) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MotionSpec.Arrival.DURATION_MS,
                    delayMillis = MotionSpec.Arrival.delayMs(index),
                    easing = MotionSpec.Arrival.EASING.toCompose(),
                ),
            )
        }
    }

    this.graphicsLayer {
        val p = progress.value
        alpha = p
        scaleX = MotionSpec.Arrival.FROM_SCALE + (1f - MotionSpec.Arrival.FROM_SCALE) * p
        scaleY = scaleX
        translationY = MotionSpec.Arrival.FROM_TRANSLATION_Y_DP.dp.toPx() * (1f - p)
    }
}

/**
 * Press feedback. Kept as a modifier rather than a component so tiles, chips and bar
 * buttons all respond identically.
 */
fun Modifier.pressScale(onClick: () -> Unit): Modifier = composed {
    val scale = remember { Animatable(1f) }

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    scale.animateTo(MotionSpec.Press.SCALE, tween(MotionSpec.Press.DURATION_MS))
                    // Waits for release or cancellation, then springs back either way.
                    tryAwaitRelease()
                    scale.animateTo(1f, tween(MotionSpec.Press.DURATION_MS))
                },
                onTap = { onClick() },
            )
        }
}

/** How much of the tile's own radius the halo extends past, at full pulse. */
private const val HALO_STRENGTH = 0.6f
