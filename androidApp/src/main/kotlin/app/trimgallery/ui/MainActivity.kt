package app.trimgallery.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * The single Android host (ARCHITECTURE.md § 3, § 11).
 *
 * Every screen is Compose Multiplatform and lives under `shared/feature`; this Activity
 * exists to host the navigation graph and to own the things only a platform can do —
 * share sheets, permission dialogs, document pickers, biometrics — and to feed the
 * design system the platform's accessibility settings.
 *
 * The gallery grid and viewer are milestone 8; `GalleryHost` mounts them.
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrimTheme(
                // BUILD.md § 9 opens dark; a setting flips it, the OS does not.
                dark = true,
                reduceMotion = isReduceMotionEnabled(),
            ) {
                GalleryHost(modifier = Modifier.fillMaxSize())
            }
        }
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
