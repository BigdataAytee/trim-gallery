package app.trimgallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.trimgallery.core.ui.grid.DateSections
import app.trimgallery.core.ui.grid.FastScroll
import app.trimgallery.core.ui.theme.TrimTheme
import kotlinx.datetime.LocalDate

/**
 * The date scrubber down the right edge (BUILD.md § 9: "Grid with fast-scroll date bar").
 *
 * Appears only while the user is actually scrolling or dragging it — BUILD.md § 9 asks
 * for chrome that fades when idle, and a permanent bar over the photographs would be the
 * opposite of that.
 *
 * All the arithmetic lives in [FastScroll], which is unit tested; this draws it.
 */
@Composable
fun <T> FastScrollBar(
    sections: List<DateSections.Section<T>>,
    today: LocalDate,
    thumbFraction: Float,
    visible: Boolean,
    onScrubTo: (itemIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sections.isEmpty()) return

    val colors = TrimTheme.colors
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxHeight().width(TRACK_WIDTH_DP.dp)) {
        val trackHeight = maxHeight
        val ticks = remember(sections, today) { FastScroll.ticks(sections, today) }

        // Labels sit against the track. They are the only part that says anything, so
        // they stay legible even when the thumb itself has faded.
        if (visible || dragging) {
            ticks.forEach { tick ->
                BasicText(
                    text = tick.label,
                    style = TrimTheme.typography.chip.copy(color = colors.muted),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, (tick.fraction * trackHeight.toPx()).toInt()) }
                        .padding(end = LABEL_INSET_DP.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (thumbFraction * (trackHeight - THUMB_HEIGHT_DP.dp).toPx()).toInt()) }
                .padding(end = THUMB_INSET_DP.dp)
                .size(THUMB_WIDTH_DP.dp, THUMB_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(percent = HALF))
                .background(if (dragging) colors.accent else colors.card)
                .pointerInput(sections) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            val fraction = (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                            onScrubTo(FastScroll.indexAt(sections, fraction))
                        },
                    )
                },
        )
    }
}

private const val TRACK_WIDTH_DP = 56
private const val THUMB_WIDTH_DP = 6
private const val THUMB_HEIGHT_DP = 48
private const val THUMB_INSET_DP = 6
private const val LABEL_INSET_DP = 18
private const val HALF = 50
