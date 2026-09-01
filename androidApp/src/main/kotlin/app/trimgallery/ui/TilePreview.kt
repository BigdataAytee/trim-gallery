package app.trimgallery.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.trimgallery.core.model.MediaItem
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

    val player = remember(item.platformRef.value) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(item.platformRef.value))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
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
