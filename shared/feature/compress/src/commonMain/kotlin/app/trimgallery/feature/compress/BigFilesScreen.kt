package app.trimgallery.feature.compress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.trimgallery.core.domain.skip.SkipList
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.ui.format.MediaFormatting
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * What is big, what it could become, and what cannot be helped.
 *
 * The screen the whole utility exists for. Two sections, and the second is not a
 * consolation prize: a scan that finds nothing compressible still has to tell the user
 * where their storage went, or it was a waste of their time and their battery. Somebody
 * whose library is already efficient learns that in one screen instead of wondering why the
 * app does nothing.
 *
 * **Every number here is measured or projected, never invented.** The current size is what
 * the scan recorded. The estimate is the Triager's own projection — the same number the
 * night pass acts on — and it is labelled as an estimate, because a "saves 200 MB" that
 * turns into 40 MB costs the user's trust in every other number this app shows them.
 */
@Composable
fun BigFilesScreen(
    candidates: List<MediaItem>,
    skipped: List<SkipList.Group>,
    scanning: Boolean,
    working: Set<String>,
    onTrim: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val colors = TrimTheme.colors

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.page).testTag(BigFilesTestTags.SCREEN),
        contentPadding = PaddingValues(TrimSpacing.INSET_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
    ) {
        item { header() }

        if (scanning) {
            item {
                BasicText(
                    text = "Looking through your folders…",
                    style = TrimTheme.typography.body.copy(color = colors.muted),
                    modifier = Modifier.testTag(BigFilesTestTags.SCANNING),
                )
            }
        }

        if (candidates.isNotEmpty()) {
            item {
                BasicText(
                    // The sum of projections, and said as one. "Could free" rather than
                    // "will free": nothing has been encoded or verified yet.
                    text = "Could free about ${MediaFormatting.bytes(candidates.sumOf { it.estSaving ?: 0L })}",
                    style = TrimTheme.typography.title.copy(color = colors.text),
                    modifier = Modifier.testTag(BigFilesTestTags.TOTAL),
                )
            }
        }

        items(candidates, key = { it.id }) { item ->
            CandidateRow(item = item, working = item.id in working, onTrim = { onTrim(item) })
        }

        if (!scanning && candidates.isEmpty()) {
            item {
                BasicText(
                    text = if (skipped.isEmpty()) {
                        "Nothing to look at yet. Add a folder and Trim will scan it."
                    } else {
                        "Nothing here can be made smaller. What is taking up the space is below."
                    },
                    style = TrimTheme.typography.body.copy(color = colors.muted),
                    modifier = Modifier.testTag(BigFilesTestTags.EMPTY),
                )
            }
        }

        if (skipped.isNotEmpty()) {
            item {
                BasicText(
                    text = "Large but can't be trimmed",
                    style = TrimTheme.typography.label.copy(color = colors.text),
                    modifier = Modifier.testTag(BigFilesTestTags.CANNOT),
                )
            }
            items(skipped, key = { it.heading }) { group -> ReasonGroup(group) }
        }
    }
}

@Composable
private fun CandidateRow(item: MediaItem, working: Boolean, onTrim: () -> Unit) {
    val colors = TrimTheme.colors
    val estimate = item.estSaving ?: 0L

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(TrimShape.CARD_DP.dp))
            .padding(TrimSpacing.CARD_PADDING_DP.dp)
            .testTag(BigFilesTestTags.row(item.id)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp)) {
            BasicText(item.name, style = TrimTheme.typography.body.copy(color = colors.text))
            BasicText(
                // Both numbers, because one of them alone is not a decision. "Now 380 MB"
                // does not say whether it is worth a tap; "saves 215 MB" does not say what
                // is being risked.
                text = "${MediaFormatting.bytes(item.size)} → about " +
                    MediaFormatting.bytes((item.size - estimate).coerceAtLeast(0)),
                style = TrimTheme.typography.caption.copy(color = colors.muted),
            )
        }
        BasicText(
            text = if (working) "Trimming…" else "Trim",
            style = TrimTheme.typography.label.copy(
                color = if (working) colors.muted else colors.accent,
            ),
            modifier = Modifier
                .then(if (working) Modifier else Modifier.pressScale(onTrim))
                .testTag(BigFilesTestTags.trim(item.id)),
        )
    }
}

/**
 * One reason, and the files it accounts for.
 *
 * Grouped rather than listed one per row, because "HDR video" said forty times is a wall of
 * text that hides the one line the user needed to read.
 */
@Composable
private fun ReasonGroup(group: SkipList.Group) {
    val colors = TrimTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(TrimShape.CARD_DP.dp))
            .padding(TrimSpacing.CARD_PADDING_DP.dp)
            .testTag(BigFilesTestTags.reason(group.heading)),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            BasicText(group.heading, style = TrimTheme.typography.label.copy(color = colors.text))
            BasicText(
                text = MediaFormatting.bytes(group.items.sumOf { it.size }),
                style = TrimTheme.typography.label.copy(color = colors.muted),
            )
        }
        BasicText(group.explanation, style = TrimTheme.typography.caption.copy(color = colors.muted))
        BasicText(
            text = if (group.count == 1) "1 file" else "${group.count} files",
            style = TrimTheme.typography.caption.copy(color = colors.muted),
        )
    }
}

private const val SMALL_GAP_DP = 6
