package app.trimgallery.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import app.trimgallery.core.model.MediaItem
import coil3.compose.AsyncImage

/**
 * One tile's picture.
 *
 * Coil per STACK.md, with `coil-video` registered in the application so a video tile shows
 * a frame rather than a blank. The model is the item's SAF document URI, which is the only
 * thing shared code knows about it and the only handle a provider will accept.
 *
 * Read-only, and it has to stay that way: this decodes through the same `ContentResolver`
 * grant everything else reads with, and nothing in the image path may open the original
 * for writing (ARCHITECTURE.md § 2.2 — originals are read-only until the one replace in
 * `SafeReplacerAndroid`).
 */
@Composable
fun Thumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    AsyncImage(
        model = item.platformRef.value,
        // The file's own name is the only description we have that is true; the indexer's
        // labels are not a caption and must never be read out as one.
        contentDescription = item.name,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}
