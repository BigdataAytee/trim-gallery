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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * The folders this app is allowed to read, and what happens to the originals in each.
 *
 * Its own screen since the pivot (BUILD.md § 9, screen 3). It used to be the first section
 * of Settings, which was the right home while the app was a gallery and folders were a
 * setup detail. In a utility they are the product: nothing runs until one is granted, and
 * the mode chosen here decides whether an original is kept, moved or eventually deleted.
 *
 * @param onWholePhone opens the explanation for whole-phone access, never the system
 *   dialog. BUILD.md § 4 (b) is explicit that the reason comes before the request, and
 *   that everything works without it.
 */
@Composable
fun FoldersScreen(
    folders: List<FolderRow>,
    onFolderMode: (FolderRow, FolderMode) -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (FolderRow) -> Unit,
    onWholePhone: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val colors = TrimTheme.colors

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.page).testTag(FoldersTestTags.SCREEN),
        contentPadding = PaddingValues(TrimSpacing.INSET_DP.dp),
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
    ) {
        item { header() }

        if (folders.isEmpty()) {
            item {
                BasicText(
                    text = "No folders yet. Nothing is read, and nothing runs, until you add one.",
                    style = TrimTheme.typography.body.copy(color = colors.muted),
                    modifier = Modifier.testTag(FoldersTestTags.EMPTY),
                )
            }
        }

        items(folders, key = { it.ref }) { folder ->
            FolderCard(
                folder = folder,
                onMode = { mode -> onFolderMode(folder, mode) },
                onRemove = { onRemoveFolder(folder) },
            )
        }

        item {
            TextButton(
                text = "Add a folder",
                onClick = onAddFolder,
                modifier = Modifier.testTag(FoldersTestTags.ADD),
            )
        }

        item {
            TextButton(
                text = "Scan my whole phone",
                onClick = onWholePhone,
                modifier = Modifier.testTag(FoldersTestTags.WHOLE_PHONE),
            )
        }
    }
}

/**
 * What whole-phone access is, before Android is asked for it.
 *
 * BUILD.md § 4 (b): the explanation comes first, and the honest half of it is the last
 * line — everything already works without this. A permission screen that only lists what
 * the app gains is a screen written for the app.
 */
@Composable
fun WholePhoneExplainer(onContinue: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TrimTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
        modifier = modifier
            .fillMaxSize()
            .background(colors.page)
            .padding(TrimSpacing.INSET_DP.dp)
            .testTag(FoldersTestTags.WHOLE_PHONE_EXPLAINER),
    ) {
        BasicText("Scan my whole phone", style = TrimTheme.typography.title.copy(color = colors.text))
        BasicText(
            text = "Android calls this All files access. It lets Trim look in Downloads and in " +
                "app folders, which the folder picker cannot reach.",
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )
        BasicText(
            text = "It is a broad permission, and Google reviews it strictly. Trim still never " +
                "sends anything anywhere — it has no internet permission at all.",
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )
        BasicText(
            text = "You do not need it. Every part of this app works on the folders you add " +
                "yourself, and you can turn this off later in Android's settings.",
            style = TrimTheme.typography.body.copy(color = colors.text),
        )
        TextButton(
            text = "Continue to Android's permission screen",
            onClick = onContinue,
            modifier = Modifier.testTag(FoldersTestTags.WHOLE_PHONE_CONTINUE),
        )
        TextButton(text = "Not now", onClick = onCancel)
    }
}

@Composable
private fun FolderCard(folder: FolderRow, onMode: (FolderMode) -> Unit, onRemove: () -> Unit) {
    val colors = TrimTheme.colors

    Column(
        verticalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(TrimShape.CARD_DP.dp))
            .padding(TrimSpacing.CARD_PADDING_DP.dp)
            .testTag(FoldersTestTags.row(folder.ref)),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicText(folder.displayName, style = TrimTheme.typography.label.copy(color = colors.text))
            BasicText(
                text = "Remove",
                style = TrimTheme.typography.label.copy(color = colors.accent),
                modifier = Modifier
                    .pressScale(onRemove)
                    .testTag(FoldersTestTags.remove(folder.ref)),
            )
        }

        FolderMode.entries.forEach { mode ->
            ModeRow(
                folderRef = folder.ref,
                mode = mode,
                selected = folder.mode == mode,
                offloadTarget = folder.offloadTarget,
                onSelect = onMode,
            )
        }
    }
}

@Composable
private fun ModeRow(
    folderRef: String,
    mode: FolderMode,
    selected: Boolean,
    offloadTarget: String?,
    onSelect: (FolderMode) -> Unit,
) {
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
            .padding(vertical = SMALL_GAP_DP.dp)
            .testTag(FoldersTestTags.mode(folderRef, mode.name)),
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

private const val SMALL_GAP_DP = 6
private const val BULLET_DP = 5
private const val BULLET_TOP_DP = 5
