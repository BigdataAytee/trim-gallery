package app.trimgallery.core.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The ring over a thumbnail the night pass is working on (DESIGN_SYSTEM.md,
 * `progress-ring`).
 *
 * @param progress 0..1 when the step can say how far along it is, `null` when it is
 *   working but cannot. Null spins; a number grows. Nothing here ever invents a
 *   fraction — a ring that fills to 90% and stops is a worse answer than one that
 *   only ever said "busy".
 */
@Composable
fun ProgressRing(
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val density = LocalDensity.current
    val stroke = remember(density) {
        with(density) { Stroke(width = MotionSpec.ProgressRing.STROKE_DP.dp.toPx()) }
    }

    if (progress != null) {
        val sweep = remember { Animatable(0f) }
        LaunchedEffect(progress, reduceMotion) {
            val target = progress.coerceIn(0f, 1f) * FULL_TURN
            if (reduceMotion) {
                sweep.snapTo(target)
            } else {
                sweep.animateTo(target, MotionSpec.ProgressRing.SPRING.toCompose())
            }
        }
        Canvas(modifier) {
            drawArc(
                color = color,
                startAngle = START_ANGLE,
                sweepAngle = sweep.value,
                useCenter = false,
                style = stroke,
            )
        }
        return
    }

    // Indeterminate. Reduce-motion gets a static arc rather than a spinner: DESIGN_SYSTEM
    // turns springs into short eases and drops count-ups, and a perpetual rotation is the
    // one thing on this screen that never settles.
    if (reduceMotion) {
        Canvas(modifier) {
            drawArc(
                color = color,
                startAngle = START_ANGLE,
                sweepAngle = MotionSpec.ProgressRing.SPIN_SWEEP_DEGREES,
                useCenter = false,
                style = stroke,
            )
        }
        return
    }

    val spin = rememberInfiniteTransition(label = "progress-ring-spin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(
            animation = tween(MotionSpec.ProgressRing.SPIN_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress-ring-angle",
    )
    Canvas(modifier) {
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = MotionSpec.ProgressRing.SPIN_SWEEP_DEGREES,
            useCenter = false,
            style = stroke,
        )
    }
}

/** Twelve o'clock, so a ring at 25% reads as a quarter turn rather than an arbitrary arc. */
private const val START_ANGLE = -90f
private const val FULL_TURN = 360f
