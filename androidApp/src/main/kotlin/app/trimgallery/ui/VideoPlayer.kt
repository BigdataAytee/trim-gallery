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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.trimgallery.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as Media3Item

/**
 * Video playback in the viewer, behind the `video` slot `shared/feature/gallery` exposes.
 *
 * The slot exists so the shared module depends on no player at all — the same reason
 * `artwork` is a slot rather than a Coil call. ExoPlayer is Android-only and iOS will
 * supply `AVPlayer` against the same slot (ARCHITECTURE.md § 6).
 *
 * This is playback, not encoding. It decodes through Media3 like any player, and the
 * hardware-only rule in BUILD.md § 2 rule 2 is about the *encoder* the night pass
 * chooses: nothing here creates a codec, and `CodecFactory` remains the only door to one.
 */
@UnstableApi
@Composable
fun VideoPlayer(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Keyed on the item, so paging to another video builds a new player rather than
    // re-pointing this one — a re-used player carries the previous item's position and
    // shows a frame of the wrong video before it seeks.
    // Looper stated, not inherited from whichever thread composed this. See TilePreview for
    // the failure this is: `ExoPlayer.Builder` defaults to the current thread's looper, a
    // worker thread has none so it falls back to main, and every later call from that
    // worker throws "Player is accessed on the wrong thread". Compose promises nothing
    // about the composing thread.
    val player = remember(item.platformRef.value) {
        ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()
    }

    LaunchedEffect(player) {
        withContext(Dispatchers.Main) {
            player.setMediaItem(Media3Item.fromUri(item.platformRef.value))
            // The user tapped this video; playing it is the whole intent of the tap.
            player.playWhenReady = true
            // Sound on: this is the viewer, not a grid preview. Muted autoplay belongs to
            // the tile, and that is a separate piece of work.
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.prepare()
        }
    }

    // Backgrounding the app must stop playback: BUILD.md rule 7 is about the night pass
    // yielding to the foreground, and a player that keeps decoding behind a locked screen
    // is the same discourtesy pointed the other way.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Lifecycle events arrive on main, which is the player's looper, so this
                // one is already where it needs to be.
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Released when the composable leaves — a player that outlives its page holds a
    // decoder the next one needs.
    DisposableEffect(player) {
        // Posted rather than called: onDispose runs on the applier thread, and release is
        // a player access like any other.
        onDispose { Handler(Looper.getMainLooper()).post { player.release() } }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                // The viewer already owns dismissal by drag; the player's own controls
                // must not eat those gestures when they are hidden.
                controllerAutoShow = false
            }
        },
        onRelease = { it.player = null },
    )
}
