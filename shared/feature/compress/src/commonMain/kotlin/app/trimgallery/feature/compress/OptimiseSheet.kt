package app.trimgallery.feature.compress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.trimgallery.core.domain.compress.CompressNow
import app.trimgallery.core.domain.compress.OptimiseFlow
import app.trimgallery.core.ui.format.MediaFormatting
import app.trimgallery.core.ui.motion.ProgressRing
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * "Optimise" — one file, on the user's explicit tap (USER_JOURNEY.md § 6).
 *
 * The first place in this app where a person can make it change a file. Everything under it
 * was built and reachable only by a night pass nobody watches; this is the version they can
 * see happen, which is also the version that has to be honest about every step.
 *
 * Pure and platform-free, in the house style: a state in, callbacks out, the thumbnail
 * through a slot. Every rule it appears to apply is `OptimiseFlow`'s, unit tested on the
 * JVM — in particular *whether Undo may be offered at all*, which depends on an original
 * having actually been parked.
 *
 * ## What it deliberately does not say
 *
 * The estimate is shown only where something measured one. `CompressNow.Estimate` leaves
 * both numbers nullable precisely so that a sheet with no prediction says how big the file
 * is and offers to start, rather than inventing a saving the result will contradict. A
 * "saves about 200 MB" that turns into 40 MB costs the user's trust in every other number
 * this app shows them.
 *
 * ## Why it differs from USER_JOURNEY § 6's three buttons
 *
 * § 6 ends on Share / Replace original / Keep both — three choices made *before* anything is
 * written. What this ends on is Keep / Undo, which is the same safety with the order
 * reversed: `VideoOptimiseStep` replaces as the last step of a verified chain, and the
 * original is parked rather than deleted, so "Undo" is a real restore rather than a promise.
 * Building § 6's version would mean a second write path that holds a finished encode
 * unreplaced, and the shape the field report asked for is the one the engine already
 * supports. Recorded in PROJECT.md; Share and Keep both are still open.
 */
@Composable
fun OptimiseSheet(
    state: OptimiseFlow.State,
    onStart: () -> Unit,
    onKeep: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit = {},
) {
    val item = state.item ?: return
    val colors = TrimTheme.colors

    Column(
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.CARD_PADDING_DP.dp),
        modifier = modifier
            .testTag(OptimiseTestTags.SHEET)
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(TrimShape.SHEET_DP.dp))
            .padding(TrimSpacing.CARD_PADDING_DP.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TrimSpacing.CARD_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.size(THUMB_DP.dp)) { artwork() }
            Column(verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                BasicText(item.name, style = TrimTheme.typography.label.copy(color = colors.text))
                BasicText(
                    text = MediaFormatting.bytes(item.size),
                    style = TrimTheme.typography.caption.copy(color = colors.muted),
                )
            }
        }

        when (state) {
            is OptimiseFlow.State.Offered -> Offer(state, onStart, onDismiss)
            is OptimiseFlow.State.Refused -> Refusal(state, onDismiss)
            is OptimiseFlow.State.Working -> Working(state)
            is OptimiseFlow.State.Done -> Done(state, onKeep, onUndo)
            is OptimiseFlow.State.Undone -> Undone(onDismiss)
            OptimiseFlow.State.Closed -> Unit
        }
    }
}

/** The estimate, and Start. */
@Composable
private fun Offer(state: OptimiseFlow.State.Offered, onStart: () -> Unit, onDismiss: () -> Unit) {
    val colors = TrimTheme.colors
    val estimate = state.decision.estimate

    Column(
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        modifier = Modifier.testTag(OptimiseTestTags.ESTIMATE),
    ) {
        BasicText(
            text = estimateLine(estimate),
            style = TrimTheme.typography.body.copy(color = colors.text),
        )

        // USER_JOURNEY § 6: "note 'Uses battery' shown once". Once, because a warning shown
        // every time is read as boilerplate and stops carrying information.
        if (state.decision.showBatteryNote) {
            BasicText(
                text = "This one runs now, on battery.",
                style = TrimTheme.typography.caption.copy(color = colors.muted),
            )
        }

        // Not a refusal: the user asked, and it is their battery. But the likely outcome is
        // "no smaller version was possible", and a user who was told that first reads it as
        // the app being careful rather than broken.
        if (state.decision.unlikelyToHelp) {
            BasicText(
                text = "Trim doesn't expect this one to get much smaller.",
                style = TrimTheme.typography.caption.copy(color = colors.warning),
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(TrimSpacing.CARD_PADDING_DP.dp)) {
        Action("Optimise", onStart, Modifier.testTag(OptimiseTestTags.START))
        Action("Not now", onDismiss, muted = true)
    }
}

/**
 * What the sheet may promise before it starts.
 *
 * Three sentences rather than one with holes in it, because a missing estimate and a missing
 * time are different facts and neither is worth faking. `expectedNewSize` is null until
 * triage has probed the file, and `expectedMs` until the predictor has watched this device
 * finish about twenty encodes.
 */
private fun estimateLine(estimate: CompressNow.Estimate): String {
    val newSize = estimate.expectedNewSize
    val ms = estimate.expectedMs
    return when {
        newSize != null && ms != null ->
            "About ${MediaFormatting.bytes(newSize)} afterwards, in around ${minutes(ms)}."
        newSize != null -> "About ${MediaFormatting.bytes(newSize)} afterwards."
        else -> "Trim hasn't measured this kind of file yet, so it can't say how much it will save."
    }
}

private fun minutes(ms: Long): String {
    val totalSeconds = ms / MS_PER_SECOND
    return if (totalSeconds < SECONDS_PER_MINUTE) {
        "$totalSeconds seconds"
    } else {
        val whole = totalSeconds / SECONDS_PER_MINUTE
        if (whole == 1L) "a minute" else "$whole minutes"
    }
}

@Composable
private fun Refusal(state: OptimiseFlow.State.Refused, onDismiss: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        modifier = Modifier.testTag(OptimiseTestTags.REFUSAL),
    ) {
        BasicText(
            text = state.decision.explanation,
            style = TrimTheme.typography.body.copy(color = TrimTheme.colors.text),
        )
        Action("Close", onDismiss, muted = true)
    }
}

@Composable
private fun Working(state: OptimiseFlow.State.Working) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(TrimSpacing.CARD_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(OptimiseTestTags.PROGRESS),
    ) {
        // A null progress draws the indeterminate ring rather than a bar pinned at zero.
        ProgressRing(
            progress = state.progress,
            color = TrimTheme.colors.accent,
            modifier = Modifier.size(RING_DP.dp),
        )
        BasicText(
            text = "Optimising. You can leave this screen.",
            style = TrimTheme.typography.body.copy(color = TrimTheme.colors.muted),
        )
    }
}

/**
 * The result, and the two things the user may do about it.
 *
 * Undo appears only when [OptimiseFlow.State.Done.mayUndo] — which means an original was
 * actually parked and the restore has somewhere to restore from. A skip or a failure changed
 * nothing, so there is nothing to offer and the sheet says so instead.
 */
@Composable
private fun Done(state: OptimiseFlow.State.Done, onKeep: () -> Unit, onUndo: () -> Unit) {
    val colors = TrimTheme.colors

    BasicText(
        text = state.summary,
        style = TrimTheme.typography.title.copy(
            color = if (state.changedTheFile) colors.text else colors.muted,
        ),
        modifier = Modifier.testTag(OptimiseTestTags.SUMMARY),
    )

    if (state.mayUndo) {
        BasicText(
            text = "The original is in the bin until you say otherwise.",
            style = TrimTheme.typography.caption.copy(color = colors.muted),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TrimSpacing.CARD_PADDING_DP.dp)) {
            Action("Keep it", onKeep, Modifier.testTag(OptimiseTestTags.KEEP))
            Action("Undo", onUndo, Modifier.testTag(OptimiseTestTags.UNDO), muted = true)
        }
    } else {
        Action("Close", onKeep, Modifier.testTag(OptimiseTestTags.KEEP), muted = true)
    }
}

@Composable
private fun Undone(onDismiss: () -> Unit) {
    BasicText(
        text = "Put back. Your original is where it was.",
        style = TrimTheme.typography.body.copy(color = TrimTheme.colors.text),
        modifier = Modifier.testTag(OptimiseTestTags.SUMMARY),
    )
    Action("Close", onDismiss, muted = true)
}

@Composable
private fun Action(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, muted: Boolean = false) {
    BasicText(
        text = label,
        style = TrimTheme.typography.label.copy(
            color = if (muted) TrimTheme.colors.muted else TrimTheme.colors.accent,
        ),
        modifier = modifier.pressScale(onClick).padding(vertical = GAP_DP.dp),
    )
}

private const val GAP_DP = 6
private const val RING_DP = 44
private const val THUMB_DP = 56
private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
