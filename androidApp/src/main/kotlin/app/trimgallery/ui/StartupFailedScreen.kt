package app.trimgallery.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.DiagnosticsExport
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.StartupGuard

/**
 * What is left when the dependency graph itself did not build.
 *
 * The recovery screen, wired by hand. Every dependency it needs takes a `Context` and
 * nothing else, so this screen exists in the one situation where `koinInject` cannot be
 * called — which is also the situation in which the user most needs to be told something.
 *
 * There is no "try again" here. The graph fails identically on every launch, so retrying is
 * the loop; the honest last action is to close, and to send the file first.
 */
@Composable
fun StartupFailedScreen(failure: Throwable?, modifier: Modifier = Modifier, onClose: () -> Unit = {}) {
    val context = LocalContext.current
    RecoveryScreen(
        onContinue = onClose,
        modifier = modifier,
        headline = "Trim could not start",
        explanation = "Something failed while the app was being put together, before any " +
            "screen existed — ${failure?.let { it::class.simpleName } ?: "reason unrecorded"}. " +
            "Nothing in your folders has been touched. Please send the diagnostics: this " +
            "file is the only record of what happened.",
        continueLabel = "Close",
        guard = StartupGuard(context),
        crashes = CrashReports(context),
        export = DiagnosticsExport(context),
        folders = GrantedFolders(context),
    )
}
