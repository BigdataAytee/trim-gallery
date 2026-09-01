package app.trimgallery.ui

import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import app.trimgallery.TrimGalleryApplication
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.android.StartupGuard
import org.koin.android.ext.android.get

/**
 * The single Android host (ARCHITECTURE.md § 3, § 11).
 *
 * Every screen is Compose Multiplatform and lives under `shared/feature`; this Activity
 * exists to host the navigation graph and to own the things only a platform can do —
 * share sheets, permission dialogs, document pickers, biometrics — and to feed the
 * design system the platform's accessibility settings.
 *
 * It also owns the launch mark. `onCreate` sets it and the first frame clears it, so a
 * crash anywhere between the two — the graph, this method, the first composition, the
 * `koinInject` calls, reading the platform's granted folders — leaves it set and the next
 * launch opens the recovery screen instead of repeating itself. The previous version of
 * that mark started inside the composition and so covered none of those; a phone came back
 * with "Trim Gallery keeps stopping" from a crash in the part it did not cover.
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Resolved first, and read before it is written: `previousRunFailed` is computed at
        // construction, so anything that constructs the guard after `beginLaunch` would get
        // the answer for this launch rather than the last one.
        //
        // `runCatching`, because resolving from the graph is one of the things that can
        // fail — and a guard that threw while trying to record that the launch might throw
        // would be a joke at the user's expense.
        val guard = runCatching { get<StartupGuard>() }.getOrNull()
        guard?.beginLaunch()
        guard?.let(::clearTheMarkOnFirstFrame)

        val failure = TrimGalleryApplication.startupFailure
        if (failure != null || guard == null) {
            // There is no graph, so there is no gallery, no Settings and no recovery screen
            // — every one of them resolves from it. What is left is a screen that needs
            // nothing but a Context, and the way to the diagnostics that explain why.
            setContent {
                TrimTheme(dark = true, reduceMotion = isReduceMotionEnabled()) {
                    StartupFailedScreen(failure = failure, modifier = Modifier.fillMaxSize())
                }
            }
            return
        }

        setContent {
            TrimTheme(
                // BUILD.md § 9 opens dark; a setting flips it, the OS does not.
                dark = true,
                reduceMotion = isReduceMotionEnabled(),
            ) {
                TrimApp(modifier = Modifier.fillMaxSize())
            }
        }
    }

    /**
     * Clears the launch mark the first time this window is about to draw.
     *
     * Pre-draw rather than post-resume, and the difference is the whole point: `onResume`
     * runs before the content has been measured, so an Activity that reaches RESUMED and
     * then dies composing its first screen would have cleared the mark on its way past.
     * That is not hypothetical — "reaches RESUMED" was the only thing this app's
     * instrumented test asserted for four milestones, on builds whose first screen was
     * broken. A pre-draw callback fires after the composition has measured and laid out,
     * and before anything is on the glass, so it means what it says.
     */
    private fun clearTheMarkOnFirstFrame(guard: StartupGuard) {
        val root = window.decorView
        root.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    guard.completeLaunch()
                    return true
                }
            },
        )
    }

    /**
     * Whether the system animation scale has been turned off, which is Android's signal
     * for "reduce motion" (DESIGN_SPEC.md § 4.6 has the same requirement on the web).
     */
    private fun isReduceMotionEnabled(): Boolean = android.provider.Settings.Global.getFloat(
        contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        DEFAULT_ANIMATION_SCALE,
    ) == 0f

    private companion object {
        const val DEFAULT_ANIMATION_SCALE = 1f
    }
}
