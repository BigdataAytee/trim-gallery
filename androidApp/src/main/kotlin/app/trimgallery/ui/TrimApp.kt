package app.trimgallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
 * The app's three screens, and which of them is on top.
 *
 * Still no navigation library, and the third screen is where that was to be re-decided: a
 * back stack that is never deeper than one, with no arguments to pass and no deep links to
 * answer, is an enum. A library would be a dependency STACK.md would have to be asked about
 * to buy nothing. Revisit when a screen has to open another screen.
 *
 * `remember` rather than `rememberSaveable`, so rotating the phone off a subscreen returns
 * to Home. `rememberSaveable` lives in `runtime-saveable`, which nothing on this
 * classpath exports — `compose.runtime`, `compose.foundation` and `compose.animation` all
 * omit it — so keeping this across a configuration change means adding a dependency, and
 * STACK.md says ask first. Recorded in PROJECT.md as a follow-up.
 *
 * The other two are reached from controls drawn *over* Home rather than from a bar above
 * it: a permanent toolbar spends a strip of every screen on buttons used twice a month.
 */
@UnstableApi
@Composable
fun TrimApp(startInRecovery: Boolean = false, modifier: Modifier = Modifier) {
    var screen by remember { mutableStateOf(Screen.HOME) }

    // Passed in rather than read here, and that is deliberate. The answer depends on being
    // asked before this launch marks itself, which only `MainActivity` is in a position to
    // do — a composable that resolved the guard and asked would be asking after the mark was
    // already set, and would send every launch to the recovery screen.
    //
    // Set again if this launch's own startup work fails. Either way the gallery is not
    // restarted: the work that failed is work the app does by itself, so trying it again is
    // the loop rather than a retry.
    var recovering by remember { mutableStateOf(startInRecovery) }

    // The system back gesture returns to the photographs rather than closing the app. Only
    // off the gallery — disabled, the gesture falls through to the Activity, which is what
    // should happen on the gallery itself.
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    if (recovering) {
        RecoveryScreen(onContinue = { recovering = false }, modifier = modifier.fillMaxSize())
        return
    }

    Box(modifier.fillMaxSize()) {
        when (screen) {
            Screen.SETTINGS -> SettingsHost(onBack = { screen = Screen.HOME }, modifier = Modifier.fillMaxSize())
            Screen.HISTORY -> HistoryHost(onBack = { screen = Screen.HOME }, modifier = Modifier.fillMaxSize())
            Screen.FOLDERS -> FoldersHost(onBack = { screen = Screen.HOME }, modifier = Modifier.fillMaxSize())
            Screen.BIG_FILES -> BigFilesHost(onBack = { screen = Screen.HOME }, modifier = Modifier.fillMaxSize())
            Screen.HOME -> HomeHost(
                modifier = Modifier.fillMaxSize(),
                onStartupFailure = { recovering = true },
                onFolders = { screen = Screen.FOLDERS },
                onFindBigFiles = { screen = Screen.BIG_FILES },
                chrome = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(SMALL_GAP_DP.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .systemBarsPadding()
                            .padding(TrimSpacing.INSET_DP.dp),
                    ) {
                        Pill("History") { screen = Screen.HISTORY }
                        Pill("Settings") { screen = Screen.SETTINGS }
                    }
                },
            )
        }
    }
}

/** What is on top. One level deep, which is the whole of this app's navigation. */
private enum class Screen { HOME, BIG_FILES, FOLDERS, SETTINGS, HISTORY }

/**
 * The way off the grid: a small pill over its top-right corner.
 *
 * Worded rather than drawn. A glyph would need an icon set this project does not have, and
 * the shortest true label is better than a picture of one.
 */
@Composable
private fun Pill(label: String, onOpen: () -> Unit) {
    val colors = TrimTheme.colors
    Box(
        modifier = Modifier
            .pressScale(onOpen)
            .background(colors.card, RoundedCornerShape(TrimShape.BUTTON_DP.dp))
            .padding(horizontal = TrimSpacing.CARD_PADDING_DP.dp, vertical = PILL_V_DP.dp),
    ) {
        BasicText(label, style = TrimTheme.typography.label.copy(color = colors.accent))
    }
}

private const val PILL_V_DP = 8
private const val SMALL_GAP_DP = 8
