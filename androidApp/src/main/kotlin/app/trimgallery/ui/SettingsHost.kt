package app.trimgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.trimgallery.core.domain.billing.Entitlements
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.SettingsStore
import app.trimgallery.engine.android.BuildIdentity
import app.trimgallery.feature.settings.SettingsScreen
import app.trimgallery.feature.settings.SettingsTestTags
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings, bound to the two things that hold its state.
 *
 * They are deliberately two. The **platform** owns which folders are granted — persisted
 * URI permissions survive the database being cleared and are the only thing that decides
 * whether a scan will succeed — and the **database** owns what to do with the originals
 * inside them, keyed on the tree URI both sides share. So a folder disappears from this
 * list when the user revokes it in system Settings, and its mode comes back if they ever
 * grant it again.
 *
 * Everything the screen itself needs is a value or a callback; the screen names no
 * platform, which is what keeps it compiling for iOS.
 */
@Composable
fun SettingsHost(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    store: SettingsStore = koinInject(),
    tier: Tier = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val colors = TrimTheme.colors

    // Null until the store's first emission. Rendering `Settings()` in the meantime would
    // show the *defaults* for a second — Standard quality, a 60-minute cap — which is a lie
    // to anybody who has changed them, and a lie that invites a tap on the value they
    // already chose.
    val settings by store.settings.collectAsState(initial = null)

    Box(modifier.fillMaxSize().background(colors.page)) {
        settings?.let { current ->
            SettingsScreen(
                settings = current,
                onQualityTarget = { target -> scope.launch { store.update { it.copy(qualityTarget = target) } } },
                onStartWhenFull = { on -> scope.launch { store.update { it.copy(startWhenFull = on) } } },
                onNightlyCap = { minutes -> scope.launch { store.update { it.copy(nightlyCapMinutes = minutes) } } },
                onUndoRetention = { days -> scope.launch { store.update { it.copy(undoRetentionDays = days) } } },
                // Asked of the same function the store sanitises with, rather than restated
                // here: `Int.MAX_VALUE` clamps to the ceiling, so the screen's bound and the
                // store's bound cannot drift apart.
                retentionMax = Entitlements.retentionDays(tier, Int.MAX_VALUE),
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
                header = { BackToHome(onBack) },
                // The two things here that are Android's rather than the app's, so they
                // are passed in rather than written into the shared screen.
                footer = {
                    Column(verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp)) {
                        DiagnosticsButton()
                        About()
                    }
                },
            )
        }
    }
}

/**
 * Which build this is.
 *
 * Always shown, unlike the diagnostics button beside it: the button appears only when
 * there is a crash to send, and the question this answers — "am I even on the build with
 * the fix?" — is asked most often when nothing has crashed at all.
 */
@Composable
private fun About() {
    BasicText(
        text = BuildIdentity.line,
        style = TrimTheme.typography.caption.copy(color = TrimTheme.colors.muted),
        modifier = Modifier.testTag(SettingsTestTags.ABOUT),
    )
}

@Composable
private fun BackToHome(onBack: () -> Unit) {
    BasicText(
        text = "← Home",
        style = TrimTheme.typography.label.copy(color = TrimTheme.colors.accent),
        modifier = Modifier.pressScale(onBack),
    )
}
