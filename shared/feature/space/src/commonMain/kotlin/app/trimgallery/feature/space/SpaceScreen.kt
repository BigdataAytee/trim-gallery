package app.trimgallery.feature.space

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.trimgallery.core.domain.skip.SkipList
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.StopReason
import app.trimgallery.core.ui.format.MediaFormatting
import app.trimgallery.core.ui.motion.ProgressRing
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import kotlin.time.Instant
import app.trimgallery.core.domain.space.History as HistoryRules
import app.trimgallery.core.domain.space.SpaceScreen as SpaceRules

/**
 * Space (BUILD.md § 9, USER_JOURNEY.md § 12).
 *
 * > Space screen: running total, animated progress ring during a run, history with restore,
 * > energy estimate.
 *
 * The screen that answers "is this app worth having on my phone?". Every number on it comes
 * from `SpaceScreen`, `History` and `SkipList` in core/domain, which are pure and tested;
 * this composable arranges them and adds nothing of its own. In particular it does not
 * decide what a Restore button may offer — `History.isOneTap` does, because a button that
 * appears to work and does not is the specific failure that screen exists to avoid.
 *
 * Pure and platform-free: values in, callbacks out, thumbnails through a slot.
 */
@Composable
fun SpaceScreen(
    state: SpaceRules.State,
    history: List<HistoryRules.Row>,
    skipped: List<SkipList.Group>,
    /**
     * When the night pass is expected to run, in the host's words, or null when nothing is
     * scheduled. A string rather than a time because only the platform knows: WorkManager
     * gives a state and a period, not a wall-clock moment, and inventing one here would put
     * a precise-looking lie on the calmest screen in the app.
     */
    nextRun: String?,
    formatDate: (Instant) -> String,
    onRestore: (HistoryRules.Row) -> Unit,
    onSecondaryAction: (HistoryRules.Row) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    artwork: @Composable (MediaItem) -> Unit,
) {
    val colors = TrimTheme.colors

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.page),
        contentPadding = PaddingValues(TrimSpacing.INSET_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
    ) {
        item { header() }

        item { Total(state, nextRun) }

        if (history.isNotEmpty()) {
            item { SectionTitle("What changed") }
            items(history, key = { it.job.id }) { row ->
                HistoryRow(
                    row = row,
                    formatDate = formatDate,
                    onRestore = { onRestore(row) },
                    onSecondary = { onSecondaryAction(row) },
                    artwork = artwork,
                )
            }
        }

        if (skipped.isNotEmpty()) {
            item { SectionTitle("Left alone") }
            items(skipped, key = { it.heading }) { group -> SkippedGroup(group) }
        }

        if (history.isEmpty() && skipped.isEmpty()) {
            item {
                BasicText(
                    // Not an apology and not an error: a library nothing has run over yet
                    // looks exactly like this, and so does one where everything is already
                    // efficient.
                    text = "Nothing has been optimised yet. The next run will fill this in.",
                    style = TrimTheme.typography.body.copy(color = colors.muted),
                )
            }
        }
    }
}

/**
 * The headline: everything ever freed, what a run is doing right now, and what is next.
 *
 * The projection sits under the total and is worded as a projection — `projectionLine`
 * owns that wording, because a number the app cannot vouch for must never be typeset like
 * one it can.
 */
@Composable
private fun Total(state: SpaceRules.State, nextRun: String?) {
    val colors = TrimTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
            ) {
                BasicText(
                    text = "Freed ${MediaFormatting.bytes(state.totalFreed)}",
                    style = TrimTheme.typography.title.copy(color = colors.text),
                )
                SpaceRules.projectionLine(state, MediaFormatting::bytes)?.let { line ->
                    BasicText(line, style = TrimTheme.typography.caption.copy(color = colors.muted))
                }
            }

            // Only while a run is actually happening. A ring on an idle screen is decoration
            // that reads as activity.
            (state.progress as? SpaceRules.Progress.Working)?.let { working ->
                ProgressRing(
                    progress = working.fraction,
                    color = colors.accent,
                    modifier = Modifier.size(RING_DP.dp),
                )
            }
        }

        BasicText(
            text = statusLine(state.progress, nextRun),
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )

        if (state.energyWh > 0) {
            BasicText(
                // BUILD.md § 9 puts the energy estimate here so the cost is as visible as
                // the benefit. Rounded to whole watt-hours: the estimate is not precise
                // enough to justify a decimal, and pretending otherwise is the same
                // overstatement the rest of this screen avoids.
                text = "About ${state.energyWh.toInt()} Wh of charge used so far",
                style = TrimTheme.typography.caption.copy(color = colors.muted),
            )
        }
    }
}

/**
 * One line saying what the pass is doing, or when it will next do it.
 *
 * A paused run says *why*: USER_JOURNEY.md § 3 wants a pause to be something the user is
 * told about rather than something they infer from a stalled ring, and "paused for heat" is
 * information, not an alarm.
 */
private fun statusLine(progress: SpaceRules.Progress, nextRun: String?): String = when (progress) {
    is SpaceRules.Progress.Working ->
        "Working now — ${progress.done} done, ${MediaFormatting.bytes(progress.bytesFreed)} freed tonight"
    is SpaceRules.Progress.Paused -> pauseLine(progress.reason)
    SpaceRules.Progress.Idle -> nextRun ?: "Nothing is scheduled yet. Add a folder in Settings."
}

private fun pauseLine(reason: StopReason): String = when (reason) {
    StopReason.THERMAL -> "Paused while the phone cools down."
    StopReason.UNPLUGGED -> "Paused — it runs while charging."
    StopReason.FOREGROUND -> "Paused while you are using the phone."
    StopReason.STORAGE -> "Paused — not enough free space to work safely."
    StopReason.CAP -> "Done for tonight; it reached the nightly limit."
    StopReason.CAP_FREE_TIER -> "Done for this month; it reached the free limit."
    StopReason.STOP_BY -> "Done for tonight; it stopped at the time you set."
    StopReason.COMPLETE -> "Finished everything there was to do."
}

/**
 * One changed file: before, after, and what can be done about it.
 *
 * The button is drawn from [HistoryRules.isOneTap], not from whether an undo entry exists.
 * A file offloaded to a card that is in a drawer, and an iOS original in Recently Deleted,
 * both have entries and neither can be restored by a tap.
 */
@Composable
private fun HistoryRow(
    row: HistoryRules.Row,
    formatDate: (Instant) -> String,
    onRestore: () -> Unit,
    onSecondary: () -> Unit,
    artwork: @Composable (MediaItem) -> Unit,
) {
    val colors = TrimTheme.colors

    Row(
        horizontalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(TrimShape.CARD_DP.dp))
            .padding(TrimSpacing.CARD_PADDING_DP.dp),
    ) {
        Box(modifier = Modifier.size(THUMB_DP.dp).clip(RoundedCornerShape(TrimShape.BUTTON_DP.dp))) {
            artwork(row.item)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
        ) {
            BasicText(row.item.name, style = TrimTheme.typography.label.copy(color = colors.text))

            MediaFormatting.optimisedLine(row.originalSize, row.newSize)?.let { line ->
                BasicText(line, style = TrimTheme.typography.body.copy(color = colors.muted))
            }

            BasicText(
                text = HistoryRules.restoreExplanation(row.restorable, formatDate),
                style = TrimTheme.typography.caption.copy(color = colors.muted),
            )

            if (HistoryRules.isOneTap(row.restorable)) {
                Action("Restore the original", onRestore)
            } else {
                HistoryRules.secondaryActionLabel(row.restorable)?.let { label ->
                    Action(label, onSecondary)
                }
            }
        }
    }
}

/** One reason, and the files it covers. Grouped because a flat list would be unreadable. */
@Composable
private fun SkippedGroup(group: SkipList.Group) {
    val colors = TrimTheme.colors

    Column(
        verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(TrimShape.CARD_DP.dp))
            .padding(TrimSpacing.CARD_PADDING_DP.dp),
    ) {
        BasicText(
            text = "${group.heading} · ${group.count}",
            style = TrimTheme.typography.label.copy(color = colors.text),
        )
        BasicText(group.explanation, style = TrimTheme.typography.body.copy(color = colors.muted))
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TrimTheme.typography.label.copy(color = TrimTheme.colors.accent),
        modifier = Modifier.pressScale(onClick).padding(vertical = SMALL_GAP_DP.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(text, style = TrimTheme.typography.heading.copy(color = TrimTheme.colors.text))
}

private const val SMALL_GAP_DP = 6
private const val RING_DP = 44
private const val THUMB_DP = 56
