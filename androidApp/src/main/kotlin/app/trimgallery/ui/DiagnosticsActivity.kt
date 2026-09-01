package app.trimgallery.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.DiagnosticsExport
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.StartupGuard

/**
 * A second launcher icon whose only job is to hand over the crash report.
 *
 * ## Why this exists
 *
 * Three field reports have asked for a stack trace and none has arrived, and the reason is
 * now clear: **Export diagnostics lives inside the app, and the app is what is broken.**
 * A tester whose launcher says "Trim Gallery keeps stopping" has no route to it at all. The
 * recovery screen was supposed to be that route and it is not enough — it needs the process
 * to survive far enough to compose it, over a graph that may be the thing that failed.
 *
 * So this is a separate entry point that shares almost nothing with the app. It resolves
 * nothing from Koin, opens no database, starts no scan, mounts no gallery, and reads no
 * media. It constructs four objects that each take a `Context`, reads files the crash
 * handler already wrote, and offers them to the share sheet.
 *
 * It is not the pretty part of this app. It is the part that has to work on the day nothing
 * else does.
 *
 * ## What it can and cannot survive
 *
 * `Application.onCreate` still runs before any Activity in the process, so this icon
 * depends on that method not throwing — which is why the graph and the image loader are
 * both wrapped there now. Past that, this activity has no shared failure mode with the
 * gallery: whatever kills `MainActivity` leaves this one standing.
 */
class DiagnosticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrimTheme(dark = true, reduceMotion = true) {
                RecoveryScreen(
                    onContinue = ::finish,
                    modifier = Modifier.fillMaxSize(),
                    headline = "Diagnostics",
                    explanation = "Everything Trim recorded about what went wrong. Export it and " +
                        "send the file — it names the build, the device and the exact line, which " +
                        "is what a description of the symptom cannot.",
                    continueLabel = "Close",
                    // By hand, not injected. The graph is one of the things that may have
                    // failed, and a diagnostics screen that needs the broken part to work is
                    // no diagnostics screen at all.
                    guard = StartupGuard(this),
                    crashes = CrashReports(this),
                    export = DiagnosticsExport(this),
                    folders = GrantedFolders(this),
                )
            }
        }
    }
}
