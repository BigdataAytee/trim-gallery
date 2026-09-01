package app.trimgallery.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.android.VideoThumbnails
import org.koin.compose.koinInject

/**
 * A video tile's picture.
 *
 * Separate from the photo path because Coil cannot draw one cheaply: reaching a
 * `content://` document through its fetch pipeline copies the whole video to get one frame.
 * `VideoThumbnails` explains that at length; this is what draws the result.
 *
 * **It is never black.** The tile is filled with the card colour from the first frame it is
 * composed, and the picture fades in over it when there is one. A tile with no obtainable
 * frame stays that colour — a plain empty tile, which is honest, rather than a black
 * rectangle that reads as a broken photograph.
 *
 * It deliberately does *not* keep the previous item's picture while loading. A recycled
 * tile showing the frame of a different video is worse than showing nothing: it is a
 * picture of the wrong file, and the user has no way to know that is what they are seeing.
 */
@Composable
fun VideoThumbnail(item: MediaItem, modifier: Modifier = Modifier, thumbnails: VideoThumbnails = koinInject()) {
    val colors = TrimTheme.colors

    BoxWithConstraints(modifier.fillMaxSize().background(colors.card)) {
        // The tile's own size, so a grid at three columns does not decode frames sized for
        // one at nine. Rounded down to a step so pinching the zoom does not invalidate
        // every cached thumbnail for a few pixels' difference.
        val sizePx = with(LocalDensity.current) {
            (maxWidth.toPx().toInt() / SIZE_STEP_PX).coerceAtLeast(1) * SIZE_STEP_PX
        }

        var frame by remember(item.id, sizePx) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(item.id, sizePx) {
            frame = thumbnails.frame(item.platformRef, item.mtime, sizePx)?.asImageBitmap()
        }

        // Fades in rather than appearing: a grid of tiles popping in at different moments
        // reads as jitter, and DESIGN_SYSTEM.md asks for arrival rather than appearance.
        val fade by animateFloatAsState(
            targetValue = if (frame == null) 0f else 1f,
            label = "thumbnail",
        )

        frame?.let { bitmap ->
            Image(
                bitmap = bitmap,
                // The file's own name is the only description we have that is true.
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(fade),
            )
        }
    }
}

/** Thumbnail sizes are rounded down to this, so a small zoom change reuses the cache. */
private const val SIZE_STEP_PX = 64
