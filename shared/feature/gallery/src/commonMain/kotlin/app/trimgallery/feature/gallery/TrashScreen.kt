package app.trimgallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.trimgallery.core.domain.trash.TrashPolicy
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.ui.motion.MotionSpec
import app.trimgallery.core.ui.motion.arrival
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimTheme
import kotlin.time.Instant

/**
 * Recently deleted — the undo bin (BUILD.md § 6, § 9).
 *
 * This screen is the visible half of the only promise that makes the app safe to use:
 * compression is visually lossless, not lossless, so **the original is the undo**
 * (PROJECT.md § Quality and reversibility). Every row therefore says plainly where the
 * original is and how long it will stay there — [TrashPolicy] decides the wording and is
 * unit tested, because an off-by-one in an expiry is a deleted photograph.
 */
@Composable
fun TrashScreen(
    entries: List<UndoEntry>,
    now: Instant,
    modifier: Modifier = Modifier,
    onRestore: (UndoEntry) -> Unit,
    thumbnail: @Composable (UndoEntry) -> Unit,
) {
    val colors = TrimTheme.colors
    val restorable = TrashPolicy.restorable(entries, now)

    if (restorable.isEmpty()) {
        Box(modifier.fillMaxSize().background(colors.page), contentAlignment = Alignment.Center) {
            BasicText(
                text = "Nothing here.\nOriginals appear here after an optimisation, and stay until they expire.",
                style = TrimTheme.typography.body.copy(color = colors.muted),
                modifier = Modifier.padding(PADDING_DP.dp),
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        modifier = modifier.fillMaxSize().background(colors.page),
    ) {
        itemsIndexed(restorable, key = { _, entry -> entry.id }) { index, entry ->
            TrashRow(entry, index, now, onRestore, thumbnail)
        }
    }
}

@Composable
private fun TrashRow(
    entry: UndoEntry,
    index: Int,
    now: Instant,
    onRestore: (UndoEntry) -> Unit,
    thumbnail: @Composable (UndoEntry) -> Unit,
) {
    val colors = TrimTheme.colors
    val soon = TrashPolicy.isExpiringSoon(entry, now)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .arrival(index = index, key = entry.state)
            .pressScale { onRestore(entry) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
    ) {
        Box(
            Modifier
                .size(THUMB_DP.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(MotionSpec.Hero.TILE_RADIUS_DP.dp))
                .background(colors.card),
        ) {
            thumbnail(entry)
        }

        Column(Modifier.fillMaxWidth()) {
            BasicText(
                text = TrashPolicy.subtitle(entry, now),
                // Urgency is carried by the accent, which is the only colour the design
                // system has for it — no separate warning red to learn.
                style = TrimTheme.typography.label.copy(
                    color = if (soon) colors.accent else colors.text,
                ),
            )
            BasicText(
                text = "Tap to restore",
                style = TrimTheme.typography.caption.copy(color = colors.muted),
            )
        }
    }
}

private const val PADDING_DP = 18
private const val GAP_DP = 14
private const val THUMB_DP = 64
