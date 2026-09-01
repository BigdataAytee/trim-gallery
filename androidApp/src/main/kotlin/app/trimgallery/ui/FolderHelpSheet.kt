package app.trimgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.android.FolderChoice

/**
 * What to say when the picker comes back with nothing usable.
 *
 * The rule this screen exists for: **never a bare "can't use this folder"**. Android
 * refuses three locations to every app, the app cannot tell that refusal from the user
 * changing their mind, and a message that implies the user did something wrong is both
 * unhelpful and possibly untrue.
 *
 * So it names the three, gives the way round each, and offers to open the folder that
 * works. Copy per DESIGN_SYSTEM.md: short, concrete, and never alarmed.
 */
@Composable
fun FolderHelpSheet(refusal: FolderChoice.Refusal?, onOpenCamera: () -> Unit, onPickAnother: () -> Unit) {
    val colors = TrimTheme.colors

    Box(
        modifier = Modifier.fillMaxSize().background(colors.scrim),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.card, RoundedCornerShape(topStart = SHEET_DP.dp, topEnd = SHEET_DP.dp))
                .padding(TrimSpacing.CARD_PADDING_DP.dp),
        ) {
            BasicText(
                text = when (refusal) {
                    FolderChoice.Refusal.DOWNLOADS -> "Android does not share the Downloads folder"
                    FolderChoice.Refusal.STORAGE_ROOT -> "Android does not share all of internal storage"
                    FolderChoice.Refusal.REMOVABLE_ROOT -> "Android does not share the whole SD card"
                    // Null means the picker returned nothing at all, which is either a
                    // refusal we cannot see or somebody backing out. Said neutrally.
                    null -> "No folder was chosen"
                },
                style = TrimTheme.typography.heading.copy(color = colors.text),
            )

            BasicText(
                text = "Three places are closed to every app, not just this one: the top level of " +
                    "internal storage, the Downloads folder, and the top level of an SD card. " +
                    "Any folder inside them works — including a folder inside Downloads.",
                style = TrimTheme.typography.body.copy(color = colors.muted),
            )

            BasicText(
                text = "Your photos are usually in DCIM/Camera.",
                style = TrimTheme.typography.body.copy(color = colors.muted),
            )

            Box(
                modifier = Modifier
                    .pressScale(onOpenCamera)
                    .background(colors.accent, RoundedCornerShape(TrimShape.BUTTON_DP.dp))
                    .padding(horizontal = TrimSpacing.CARD_PADDING_DP.dp, vertical = BUTTON_V_DP.dp),
            ) {
                BasicText(
                    text = "Open DCIM/Camera",
                    style = TrimTheme.typography.label.copy(color = colors.accentOn),
                )
            }

            Box(modifier = Modifier.pressScale(onPickAnother).padding(vertical = BUTTON_V_DP.dp)) {
                BasicText(
                    text = "Choose a different folder",
                    style = TrimTheme.typography.label.copy(color = colors.accent),
                )
            }
        }
    }
}

private const val SHEET_DP = 24
private const val BUTTON_V_DP = 12
