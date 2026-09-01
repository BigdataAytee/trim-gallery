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
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.DiagnosticsExport
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.WorkManagerScheduler
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * "Export diagnostics" — the only way a crash gets off the phone.
 *
 * It belongs in Settings (LAUNCH.md § Support) and Settings does not exist yet, so it
 * lives on the gallery until it does. Its home moves; the need does not: without it a
 * field report can only describe what happened, and the stack trace that names the cause
 * stays on the device.
 *
 * The button appears **only when there is something to send**, so it is invisible on a
 * healthy install rather than a permanent piece of debug furniture in a photo gallery.
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
                    val report = scheduler.status().lines(
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
