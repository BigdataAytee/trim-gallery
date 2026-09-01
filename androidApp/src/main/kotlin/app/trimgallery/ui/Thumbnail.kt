package app.trimgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.ui.theme.TrimTheme
import coil3.compose.AsyncImage

/**
 * One tile's picture.
 *
 * Coil per STACK.md for photographs, and `VideoThumbnail` for videos — see below for why
 * they differ. The model is the item's SAF document URI, which is the only thing shared
 * code knows about it and the only handle a provider will accept.
 *
 * Read-only, and it has to stay that way: this decodes through the same `ContentResolver`
 * grant everything else reads with, and nothing in the image path may open the original
 * for writing (ARCHITECTURE.md § 2.2 — originals are read-only until the one replace in
 * `SafeReplacerAndroid`).
 */
@Composable
fun Thumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    // Videos do not go through Coil. Its `VideoFrameDecoder` is registered and works, but
    // reaching a `content://` document through Coil's fetch pipeline copies the whole file
    // so the retriever has something seekable — a gigabyte read to draw one tile, which is
    // why video tiles were black on a real library. `VideoThumbnails` asks the document
    // provider first and falls back to a descriptor, and caches either to disk.
    if (item.kind == MediaKind.VIDEO) {
        VideoThumbnail(item, modifier)
        return
    }

    AsyncImage(
        model = item.platformRef.value,
        // The file's own name is the only description we have that is true; the indexer's
        // labels are not a caption and must never be read out as one.
        contentDescription = item.name,
        contentScale = ContentScale.Crop,
        // Never a black tile while a photograph decodes either: the card colour is what an
        // empty tile looks like everywhere else in the grid.
        modifier = modifier.fillMaxSize().background(TrimTheme.colors.card),
    )
}
