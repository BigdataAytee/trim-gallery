package app.trimgallery.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.android.BuildIdentity
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.DiagnosticsExport
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.StartupGuard
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * What the app shows when its own startup work killed the previous launch.
 *
 * The rule this screen exists for: **never re-run the failing work automatically.** A crash
 * in work the app starts by itself is a crash the user cannot escape, because escaping it
 * would mean not doing the thing they never asked it to do. Clearing app data was the only
 * way out of the last one, and that is not a recovery path, it is a demolition.
 *
 * So it does three things and starts nothing: shows the trace, offers to export it, and
 * lets the folder grant go — which is the input that made the work fail. "Try again anyway"
 * is there because the user may know something the app does not, but it is the last option
 * and it is never taken on their behalf.
 *
 * It names the build, because the first question about any crash is which program it was.
 *
 * Every dependency is a parameter with a `koinInject` default rather than resolved inside,
 * so that the two callers who have **no graph to inject from** can construct them by hand:
 * `MainActivity` when the graph itself failed to build, and `DiagnosticsActivity`, the
 * separate launcher icon that exists for exactly the case where nothing else in this app
 * will start. All four take nothing but a `Context`, which is what makes that possible and
 * is worth preserving.
 *
 * @param headline and [explanation] because this screen is reached three ways — after a
 *   failed launch, after a graph that never built, and on purpose from the second icon —
 *   and telling someone their app crashed when they simply tapped "Diagnostics" would be a
 *   small lie in the one place that has to be trustworthy.
 * @param continueLabel what the last action says. It is never taken on the user's behalf.
 */
@Composable
fun RecoveryScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    headline: String = "Trim stopped while it was starting up",
    explanation: String = "It has not tried again. The work that failed was reading your folder, " +
        "which it does on its own — so restarting would only do the same thing.",
    continueLabel: String = "Try again anyway",
    guard: StartupGuard = koinInject(),
    crashes: CrashReports = koinInject(),
    export: DiagnosticsExport = koinInject(),
    folders: GrantedFolders = koinInject(),
) {
    val colors = TrimTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var released by remember { mutableStateOf(false) }

    // Read once. Touching the filesystem on every frame of a screen whose whole job is to
    // be calm would be its own small joke.
    val trace = remember { crashes.asReport() }

    Column(
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
        modifier = modifier
            .fillMaxSize()
            .background(colors.page)
            .systemBarsPadding()
            .padding(TrimSpacing.INSET_DP.dp)
            .testTag(RECOVERY_TAG),
    ) {
        BasicText(
            text = headline,
            style = TrimTheme.typography.title.copy(color = colors.text),
        )
        BasicText(
            text = explanation,
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )
        BasicText(
            text = "Nothing in your folder has been changed. Trim has never had permission " +
                "to write to it.",
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )

        Action(if (released) "Folder access removed" else "Remove folder access", enabled = !released) {
            folders.releaseAll()
            released = true
        }

        Action("Export diagnostics") {
            scope.launch {
                val report = BuildIdentity.lines() + trace
                val intent = export.share(report, fileName = "trim-gallery-diagnostics.txt")
                context.startActivity(Intent.createChooser(intent, "Export diagnostics"))
            }
        }

        Action(continueLabel) {
            guard.clear()
            onContinue()
        }

        BasicText(BuildIdentity.line, style = TrimTheme.typography.caption.copy(color = colors.muted))

        // The trace itself, last and scrollable. It is the least useful thing on screen to
        // the person reading it and the most useful thing in the file they send.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.card, RoundedCornerShape(TrimShape.CARD_DP.dp))
                .padding(TrimSpacing.CARD_PADDING_DP.dp),
        ) {
            BasicText(
                text = trace.ifBlank { "No crash was recorded, which is itself worth reporting." },
                style = TrimTheme.typography.caption.copy(color = colors.muted),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = TrimTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.pressScale(onClick) else Modifier)
            .background(colors.card, RoundedCornerShape(TrimShape.BUTTON_DP.dp))
            .padding(TrimSpacing.CARD_PADDING_DP.dp),
    ) {
        BasicText(
            text = label,
            style = TrimTheme.typography.label.copy(color = if (enabled) colors.accent else colors.muted),
        )
    }
}

/** For the emulator test that asserts a broken start lands here rather than dying again. */
const val RECOVERY_TAG = "recovery-screen"
