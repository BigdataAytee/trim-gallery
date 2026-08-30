package app.trimgallery.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier

/**
 * The single Android host (ARCHITECTURE.md § 3, § 11).
 *
 * Every screen is Compose Multiplatform and lives in `shared/feature/*`; this Activity
 * exists to host the navigation graph and to own the things only a platform can do —
 * share sheets, permission dialogs, document pickers, biometrics.
 *
 * The gallery shell arrives at milestone 8.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Dark by default, media on near-black (BUILD.md § 9).
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
                    Text("Trim Gallery", modifier = Modifier.padding(insets))
                }
            }
        }
    }
}
