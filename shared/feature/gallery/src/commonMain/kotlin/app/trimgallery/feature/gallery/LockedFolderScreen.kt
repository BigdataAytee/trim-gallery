package app.trimgallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.trimgallery.core.domain.lock.LockedFolderGate
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * The locked folder (BUILD.md § 9: "Locked folder (biometric)").
 *
 * The gate is [LockedFolderGate], a unit-tested state machine; this composable renders
 * whichever state it is in and asks the platform to prompt. Nothing here decides policy,
 * which is what keeps "backgrounding always re-locks" true on both platforms.
 *
 * @param onAuthenticate ask the platform for a biometric prompt. Android supplies
 *   `BiometricPrompt`, iOS `LocalAuthentication`.
 */
@Composable
fun LockedFolderScreen(
    state: LockedFolderGate.State,
    items: List<MediaItem>,
    processingIds: Set<String>,
    now: Instant,
    today: LocalDate,
    timeZone: TimeZone,
    modifier: Modifier = Modifier,
    onAuthenticate: () -> Unit,
    artwork: @Composable (MediaItem) -> Unit,
) {
    val colors = TrimTheme.colors

    if (LockedFolderGate.isUnlocked(state, now)) {
        GalleryScreen(
            items = items,
            processingIds = processingIds,
            today = today,
            timeZone = timeZone,
            modifier = modifier,
            emptyState = {
                BasicText(
                    text = "Nothing in the locked folder.",
                    style = TrimTheme.typography.body.copy(color = colors.muted),
                )
            },
            artwork = artwork,
        )
        return
    }

    // Prompt as soon as the screen appears, rather than making the user tap twice to get
    // to something they already chose to open.
    LaunchedEffect(state) {
        if (state is LockedFolderGate.State.Locked) onAuthenticate()
    }

    Box(
        modifier = modifier.fillMaxSize().background(colors.page),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            modifier = Modifier.padding(PADDING_DP.dp),
        ) {
            BasicText(
                text = "Locked",
                style = TrimTheme.typography.title.copy(color = colors.text),
            )
            BasicText(
                // Says what the folder guarantees, not just that it is locked.
                text = "Items here are hidden from the grid, albums, search and people.",
                style = TrimTheme.typography.body.copy(color = colors.muted),
            )

            if (state is LockedFolderGate.State.Failed) {
                BasicText(
                    text = state.message,
                    style = TrimTheme.typography.label.copy(color = colors.accent),
                )
            }

            if (state !is LockedFolderGate.State.Authenticating) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(percent = HALF))
                        .background(colors.card)
                        .pressScale { onAuthenticate() }
                        .padding(horizontal = BUTTON_H_DP.dp, vertical = BUTTON_V_DP.dp),
                ) {
                    BasicText(
                        text = "Unlock",
                        style = TrimTheme.typography.label.copy(color = colors.text),
                    )
                }
            }
        }
    }
}

private const val PADDING_DP = 24
private const val GAP_DP = 12
private const val BUTTON_H_DP = 24
private const val BUTTON_V_DP = 14
private const val HALF = 50
