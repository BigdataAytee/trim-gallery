package app.trimgallery.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.NightConstraints
import app.trimgallery.engine.android.BuildIdentity
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.DiagnosticsExport
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.WorkManagerScheduler
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * "Export diagnostics" — the only way a crash gets off the phone.
 *
 * It lives in Settings, where LAUNCH.md § Support puts it, now that there is a Settings to
 * live in. Without it a field report can only describe what happened, and the stack trace
 * that names the cause stays on the device.
 *
 * The button appears **only when there is something to send**, so Settings shows no debug
 * furniture on a healthy install.
 *
 * It is one screen away from the gallery rather than on it, and that is enough for the
 * crash it is really for: a viewer that dies on a tap leaves the grid rendering perfectly
 * on the next launch. A crash during the gallery's own composition would take this button
 * down with it wherever it were placed, because the exception escapes the whole tree.
 */
@Composable
fun DiagnosticsButton(
    modifier: Modifier = Modifier,
    crashes: CrashReports = koinInject(),
    export: DiagnosticsExport = koinInject(),
    folders: GrantedFolders = koinInject(),
    scheduler: WorkManagerScheduler = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = TrimTheme.colors

    // Read once per composition rather than on every frame: this touches the filesystem,
    // and the set of crash files cannot change while the app is running without the app
    // having died in between.
    val count by remember { mutableStateOf(crashes.reports().size) }
    var sharing by remember { mutableStateOf(false) }

    if (count == 0) return

    Box(
        modifier = modifier
            .pressScale {
                if (sharing) return@pressScale
                sharing = true
                scope.launch {
                    // The scheduler section first: the commonest question a field report
                    // has to answer is "is it going to run tonight?", and that is easier to
                    // read at the top than under ten stack traces.
                    // The build first, then the scheduler, then the traces. Which
                    // program this was is the question every other line in the file
                    // depends on: a trace from a build that predates the fix it is being
                    // read against says something completely different from the same trace
                    // on a build that has it.
                    val report = BuildIdentity.lines() +
                        scheduler.status().lines(
                            constraints = NightConstraints(),
                            grantedFolders = folders.grants().size,
                        ) + "\n" + crashes.asReport()
                    val intent = export.share(report, fileName = "trim-gallery-diagnostics.txt")
                    // A chooser rather than the last-used target: the file is the user's
                    // and where it goes is their choice, every time.
                    context.startActivity(Intent.createChooser(intent, "Export diagnostics"))
                    sharing = false
                }
            }
            .background(colors.card, RoundedCornerShape(TrimShape.BUTTON_DP.dp))
            .padding(horizontal = TrimSpacing.CARD_PADDING_DP.dp, vertical = BUTTON_V_DP.dp),
    ) {
        BasicText(
            text = if (count == 1) "Export 1 crash report" else "Export $count crash reports",
            style = TrimTheme.typography.label.copy(color = colors.accent),
        )
    }
}

private const val BUTTON_V_DP = 10
