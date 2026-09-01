package app.trimgallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * The app's two screens, and which of them is on top.
 *
 * Two screens do not need a navigation library, and adding one would need asking: STACK.md
 * fixes what this app depends on. A boolean is the whole of a back stack of depth one. When
 * the third screen arrives — Space is next — this is the file that grows, and it will be an
 * honest place to decide whether it has earned a library.
 *
 * `remember` rather than `rememberSaveable`, so rotating the phone in Settings returns to
 * the photographs. `rememberSaveable` lives in `runtime-saveable`, which nothing on this
 * classpath exports — `compose.runtime`, `compose.foundation` and `compose.animation` all
 * omit it — so keeping the flag across a configuration change means adding a dependency,
 * and STACK.md says ask first. Recorded in PROJECT.md as a follow-up.
 *
 * Settings is reached from a control drawn *over* the gallery rather than from a bar above
 * it: BUILD.md § 9 wants the photographs edge to edge, and a permanent toolbar spends a
 * strip of every screen on a button that is used twice a month.
 */
@UnstableApi
@Composable
fun TrimApp(modifier: Modifier = Modifier) {
    var settingsOpen by remember { mutableStateOf(false) }

    // The system back gesture closes Settings rather than the app. Only while it is open —
    // disabled, the gesture falls through to the Activity, which is what should happen on
    // the gallery.
    BackHandler(enabled = settingsOpen) { settingsOpen = false }

    Box(modifier.fillMaxSize()) {
        if (settingsOpen) {
            SettingsHost(onBack = { settingsOpen = false }, modifier = Modifier.fillMaxSize())
        } else {
            // Handed to the gallery rather than drawn over it: the grid owns where its own
            // chrome sits in the stack, which is above the photographs and *below* the
            // viewer. Drawn here, the pill would float over an opened photograph.
            GalleryHost(
                modifier = Modifier.fillMaxSize(),
                chrome = {
                    SettingsEntry(
                        onOpen = { settingsOpen = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .systemBarsPadding()
                            .padding(TrimSpacing.INSET_DP.dp),
                    )
                },
            )
        }
    }
}

/**
 * The way in to Settings: a small pill over the top-right of the grid.
 *
 * Worded rather than drawn. A gear glyph would need an icon set this project does not
 * have, and the shortest true label is better than a picture of one.
 */
@Composable
private fun SettingsEntry(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TrimTheme.colors
    Box(
        modifier = modifier
            .pressScale(onOpen)
            .background(colors.card, RoundedCornerShape(TrimShape.BUTTON_DP.dp))
            .padding(horizontal = TrimSpacing.CARD_PADDING_DP.dp, vertical = PILL_V_DP.dp),
    ) {
        BasicText("Settings", style = TrimTheme.typography.label.copy(color = colors.accent))
    }
}

private const val PILL_V_DP = 8
