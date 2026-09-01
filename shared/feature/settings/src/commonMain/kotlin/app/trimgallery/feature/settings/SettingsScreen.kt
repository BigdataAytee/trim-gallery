package app.trimgallery.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.core.model.Settings
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * One granted folder, as this screen needs it.
 *
 * The platform owns which folders are granted; this carries the identity and what the
 * user chose to do with the originals inside it.
 */
data class FolderRow(
    /** The tree URI. The stable identity of a grant, and the key its settings hang off. */
    val ref: String,
    val displayName: String,
    val mode: FolderMode,
    /**
     * The name of the drive originals would move to under OFFLOAD, or null when there is
     * nowhere to move them.
     *
     * A second granted tree, not a path: the destination volume is written to, so it needs
     * its own persisted permission (safe-replace skill). Without one, OFFLOAD is refused
     * rather than guessed — and with one, the row says *where*, because "move originals to
     * another drive" is not a choice anybody can make without knowing which drive.
     */
    val offloadTarget: String? = null,
)

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
    folders: List<FolderRow>,
    onQualityTarget: (QualityTarget) -> Unit,
    onStartWhenFull: (Boolean) -> Unit,
    onNightlyCap: (Int) -> Unit,
    onFolderMode: (FolderRow, FolderMode) -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whatever gets the user back where they came from. Scrolls with the list. */
    header: @Composable () -> Unit = {},
    /** The platform's own additions — on Android, exporting diagnostics. */
    footer: @Composable () -> Unit = {},
) {
    val colors = TrimTheme.colors

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.page),
        contentPadding = PaddingValues(TrimSpacing.INSET_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
    ) {
        item { header() }

        item { SectionTitle("Folders") }

        if (folders.isEmpty()) {
            item {
                BasicText(
                    text = "No folders yet. The night pass has nothing to work on until you add one.",
                    style = TrimTheme.typography.body.copy(color = colors.muted),
                )
            }
        }

        items(folders, key = { it.ref }) { folder ->
            FolderCard(folder = folder, onMode = { mode -> onFolderMode(folder, mode) })
        }

        item { TextButton(text = "Add a folder", onClick = onAddFolder) }

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

        item { footer() }
    }
}

@Composable
private fun FolderCard(folder: FolderRow, onMode: (FolderMode) -> Unit) {
    val colors = TrimTheme.colors

    Column(
        verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(TrimShape.CARD_DP.dp))
            .padding(TrimSpacing.CARD_PADDING_DP.dp),
    ) {
        BasicText(folder.displayName, style = TrimTheme.typography.label.copy(color = colors.text))

        FolderMode.entries.forEach { mode ->
            ModeRow(
                mode = mode,
                selected = folder.mode == mode,
                offloadTarget = folder.offloadTarget,
                onSelect = onMode,
            )
        }
    }
}

@Composable
private fun ModeRow(mode: FolderMode, selected: Boolean, offloadTarget: String?, onSelect: (FolderMode) -> Unit) {
    val colors = TrimTheme.colors
    val available = mode != FolderMode.OFFLOAD || offloadTarget != null
    val text = when (mode) {
        FolderMode.KEEP -> "Keep originals" to "Nothing is ever removed. Uses the most space."
        FolderMode.OFFLOAD ->
            "Move originals to another drive" to
                if (offloadTarget != null) {
                    "Originals are copied to $offloadTarget, then removed from here."
                } else {
                    "Needs a second folder on an SD card or USB drive. Add one to use this."
                }
        FolderMode.FREE ->
            "Free the space" to
                "Originals go to the bin and are deleted for good after the retention period. " +
                "This is the only setting that loses a file you cannot get back."
    }

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pressScale { if (available) onSelect(mode) }
            .padding(vertical = SMALL_GAP_DP.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = BULLET_TOP_DP.dp)
                .background(
                    if (selected) colors.accent else colors.line,
                    RoundedCornerShape(BULLET_DP.dp),
                )
                .padding(BULLET_DP.dp),
        )
        Column {
            BasicText(
                text = text.first,
                style = TrimTheme.typography.body.copy(
                    color = if (available) colors.text else colors.muted,
                ),
            )
            BasicText(
                text = text.second,
                style = TrimTheme.typography.caption.copy(
                    // The consequence of FREE is warned in the accent the design system
                    // reserves for it, not buried in the same grey as everything else.
                    color = if (mode == FolderMode.FREE) colors.warning else colors.muted,
                ),
            )
        }
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
private fun Stepper(label: String, value: String, onLess: () -> Unit, onMore: () -> Unit) {
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
        BasicText(value, style = TrimTheme.typography.body.copy(color = colors.text))
        BasicText(
            text = "+",
            style = TrimTheme.typography.heading.copy(color = colors.accent),
            modifier = Modifier.pressScale(onMore),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(text, style = TrimTheme.typography.heading.copy(color = TrimTheme.colors.text))
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    BasicText(
        text = text,
        style = TrimTheme.typography.label.copy(color = TrimTheme.colors.accent),
        modifier = Modifier.pressScale(onClick).padding(vertical = SMALL_GAP_DP.dp),
    )
}

private const val SMALL_GAP_DP = 6
private const val BULLET_DP = 5
private const val BULLET_TOP_DP = 5

/** BUILD.md § 6 budgets an hour a night; the range either side of it is the useful one. */
private const val CAP_STEP = 15
private const val CAP_MIN = 15
private const val CAP_MAX = 240
