package app.trimgallery.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.core.model.Settings
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * Settings (BUILD.md § 6, USER_JOURNEY.md § Settings).
 *
 * Pure: every value comes in, every change goes out through a callback, and the module
 * names no platform. The Android host binds it to the DataStore and the grant list.
 *
 * The folder modes are the only controls here that can lose a file, and they are not
 * presented as three equal choices. *Keep originals* is always available and never
 * removes anything. *Offload* needs a second granted volume and says so when it does not
 * have one. *Free space* deletes originals after a retention period, and says that in the
 * row rather than in a help page — BUILD.md § 6 requires the warning to be shown, and a
 * radio button that reads "Free space" tells the user nothing about what it costs.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    onQualityTarget: (QualityTarget) -> Unit,
    onStartWhenFull: (Boolean) -> Unit,
    onNightlyCap: (Int) -> Unit,
    onUndoRetention: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Whatever gets the user back where they came from. Scrolls with the list. */
    header: @Composable () -> Unit = {},
    /** The platform's own additions — on Android, exporting diagnostics. */
    footer: @Composable () -> Unit = {},
) {
    val colors = TrimTheme.colors

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.page).testTag(SettingsTestTags.SCREEN),
        contentPadding = PaddingValues(TrimSpacing.INSET_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
    ) {
        item { header() }

        item { SectionTitle("Optimising") }

        item {
            Choice(
                label = "Quality",
                options = QualityTarget.entries,
                selected = settings.qualityTarget,
                nameOf = { target ->
                    when (target) {
                        // The number is the point: VMAF 95 and 90 are the gates the
                        // search actually enforces, and "Standard"/"Compact" alone would
                        // hide the only fact that distinguishes them.
                        QualityTarget.STANDARD -> "Standard — VMAF ${target.vmaf}"
                        QualityTarget.COMPACT -> "Compact — VMAF ${target.vmaf}"
                    }
                },
                onSelect = onQualityTarget,
            )
        }

        item {
            Toggle(
                label = "Start when the battery is full",
                detail = "Off means it starts once charging and idle, which is sooner and warmer.",
                checked = settings.startWhenFull,
                onChange = onStartWhenFull,
            )
        }

        item {
            Stepper(
                label = "Nightly limit",
                value = "${settings.nightlyCapMinutes} min",
                onLess = { onNightlyCap((settings.nightlyCapMinutes - CAP_STEP).coerceAtLeast(CAP_MIN)) },
                onMore = { onNightlyCap((settings.nightlyCapMinutes + CAP_STEP).coerceAtMost(CAP_MAX)) },
            )
        }

        item {
            Stepper(
                label = "Keep originals for",
                value = "${settings.undoRetentionDays} days",
                valueTag = SettingsTestTags.RETENTION,
                moreTag = SettingsTestTags.RETENTION_MORE,
                onLess = {
                    onUndoRetention((settings.undoRetentionDays - RETENTION_STEP).coerceAtLeast(RETENTION_MIN))
                },
                onMore = {
                    onUndoRetention((settings.undoRetentionDays + RETENTION_STEP).coerceAtMost(RETENTION_MAX))
                },
            )
        }

        item {
            // The one sentence this screen owes the user, next to the control that decides
            // it. Folder modes are where an original's fate is chosen; this is only how
            // long the bin holds one under "Free the space".
            BasicText(
                text = "Only folders set to Free the space ever delete an original, and only " +
                    "after this long.",
                style = TrimTheme.typography.caption.copy(color = colors.muted),
            )
        }

        item { footer() }
    }
}

@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T, nameOf: (T) -> String, onSelect: (T) -> Unit) {
    val colors = TrimTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp)) {
        BasicText(label, style = TrimTheme.typography.label.copy(color = colors.text))
        options.forEach { option ->
            BasicText(
                text = nameOf(option),
                style = TrimTheme.typography.body.copy(
                    color = if (option == selected) colors.accent else colors.muted,
                ),
                modifier = Modifier.pressScale { onSelect(option) }.padding(vertical = SMALL_GAP_DP.dp),
            )
        }
    }
}

@Composable
private fun Toggle(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = TrimTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
        modifier = Modifier.fillMaxWidth().pressScale { onChange(!checked) },
    ) {
        BasicText(
            text = (if (checked) "On — " else "Off — ") + label,
            style = TrimTheme.typography.body.copy(color = if (checked) colors.accent else colors.text),
        )
        BasicText(detail, style = TrimTheme.typography.caption.copy(color = colors.muted))
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    valueTag: String? = null,
    moreTag: String? = null,
) {
    val colors = TrimTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BasicText(label, style = TrimTheme.typography.body.copy(color = colors.text))
        BasicText(
            text = "−",
            style = TrimTheme.typography.heading.copy(color = colors.accent),
            modifier = Modifier.pressScale(onLess),
        )
        // The tag sits on the value, not on the row: a test that reads the number back has
        // to land on the node that carries the text. An unmerged Row carries none.
        BasicText(
            text = value,
            style = TrimTheme.typography.body.copy(color = colors.text),
            modifier = if (valueTag == null) Modifier else Modifier.testTag(valueTag),
        )
        BasicText(
            text = "+",
            style = TrimTheme.typography.heading.copy(color = colors.accent),
            modifier = Modifier
                .pressScale(onMore)
                .then(if (moreTag == null) Modifier else Modifier.testTag(moreTag)),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(text, style = TrimTheme.typography.heading.copy(color = TrimTheme.colors.text))
}

@Composable
internal fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        style = TrimTheme.typography.label.copy(color = TrimTheme.colors.accent),
        modifier = modifier.pressScale(onClick).padding(vertical = SMALL_GAP_DP.dp),
    )
}

private const val SMALL_GAP_DP = 6

/** BUILD.md § 6 budgets an hour a night; the range either side of it is the useful one. */
private const val CAP_STEP = 15
private const val CAP_MIN = 15
private const val CAP_MAX = 240
private const val RETENTION_STEP = 5
private const val RETENTION_MIN = 5
private const val RETENTION_MAX = 90
