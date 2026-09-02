package app.trimgallery.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.trimgallery.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as Media3Item

/**
 * A muted, silent, looping preview over a video tile the grid has settled on
 * (BUILD.md § 9). Drawn on top of the still, which stays mounted underneath.
 *
 * **Muted, and not by default — deliberately.** A gallery that makes noise because a
 * thumbnail drifted past the middle of the screen is the behaviour every user turns off
 * first. `volume = 0f` is set on the player rather than relying on a device being on
 * silent.
 *
 * One of these exists at a time: `GalleryScreen` picks a single tile. Each preview is a
 * decoder, and the grid shows six.
 */
@UnstableApi
@Composable
fun TilePreview(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Pinned to the main looper rather than left to default. `ExoPlayer.Builder` takes the
    // *current* thread's looper, and a thread without one falls back to main — so a player
    // built during composition on a worker thread ends up expecting main anyway, and every
    // later call from that same worker thread throws:
    //
    //     IllegalStateException: Player is accessed on the wrong thread.
    //     Current thread: 'DefaultDispatcher-worker-4'  Expected thread: 'main'
    //
    // Compose gives no guarantee about which thread composition runs on. It happens to be
    // main in the app today, which is why this survived to here, and it was not on the API
    // 36 emulator, which is what caught it. Stating the looper and then only touching the
    // player from that looper is the contract Media3 actually asks for.
    val player = remember(item.platformRef.value) {
        ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()
    }

    // Configuration is a player access like any other, so it happens on main rather than
    // wherever this composable was composed.
    LaunchedEffect(player) {
        withContext(Dispatchers.Main) {
            player.setMediaItem(Media3Item.fromUri(item.platformRef.value))
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.playWhenReady = true
            player.prepare()
        }
    }

    DisposableEffect(player) {
        // So is release, and onDispose runs on the applier thread — which is the same
        // worker thread the crash came from. Posting rather than calling keeps the one
        // rule this file has to obey: touch the player only on its looper.
        onDispose { Handler(Looper.getMainLooper()).post { player.release() } }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                // No controls and no touch handling: the tile owns the tap, and a preview
                // that swallowed it would break opening the viewer.
                useController = false
                controllerAutoShow = false
                isClickable = false
                isFocusable = false
            }
        },
        onRelease = { it.player = null },
    )
}
