package app.trimgallery.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.trimgallery.core.model.Album
import app.trimgallery.core.model.AlbumKind
import app.trimgallery.core.ui.motion.MotionSpec
import app.trimgallery.core.ui.motion.arrival
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimTheme

/**
 * Albums, Favourites, auto-albums, Recently deleted and the locked folder
 * (BUILD.md § 9).
 *
 * One screen rather than five, because they are one concept with different sources of
 * truth. The order is fixed rather than alphabetical: the two the user will reach for
 * most — Favourites and Recently deleted — should not move as the library grows.
 */
@Composable
fun AlbumsScreen(
    albums: List<Album>,
    modifier: Modifier = Modifier,
    onOpen: (Album) -> Unit,
    cover: @Composable (Album) -> Unit,
) {
    val colors = TrimTheme.colors

    LazyVerticalGrid(
        columns = GridCells.Fixed(ALBUM_COLUMNS),
        contentPadding = PaddingValues(PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
        modifier = modifier.fillMaxSize().background(colors.page),
    ) {
        itemsIndexed(albums, key = { _, album -> "${album.kind}-${album.id}" }) { index, album ->
            AlbumCard(
                album = album,
                index = index,
                onOpen = onOpen,
                cover = cover,
            )
        }
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    index: Int,
    onOpen: (Album) -> Unit,
    cover: @Composable (Album) -> Unit,
) {
    val colors = TrimTheme.colors

    Column(
        modifier = Modifier
            .arrival(index = index, key = albumsKey(album))
            .pressScale { onOpen(album) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(MotionSpec.Hero.TILE_RADIUS_DP.dp))
                .background(colors.card),
        ) {
            cover(album)
        }
        BasicText(
            text = album.name,
            style = TrimTheme.typography.label.copy(color = colors.text),
            modifier = Modifier.padding(top = LABEL_TOP_DP.dp),
        )
        BasicText(
            // The count is the useful fact; an album with one photo says "1 item", not
            // "1 items".
            text = if (album.count == 1) "1 item" else "${album.count} items",
            style = TrimTheme.typography.chip.copy(color = colors.muted),
        )
    }
}

/** Arrival replays when the set of albums changes shape, not on every count update. */
private fun albumsKey(album: Album): Any = album.kind

/**
 * The order the screen lists albums in.
 *
 * Favourites and Recently deleted are pinned because they are the two a user reaches for
 * without browsing; auto-albums come next because they are always there; the user's own
 * albums last, where adding one does not push everything else around.
 */
fun sortAlbums(albums: List<Album>): List<Album> = albums.sortedWith(
    compareBy<Album> {
        when (it.kind) {
            AlbumKind.FAVOURITES -> 0
            AlbumKind.RECENTLY_DELETED -> 1
            AlbumKind.LOCKED -> 2
            AlbumKind.AUTO -> 3
            AlbumKind.USER -> 4
        }
    }.thenBy { it.name },
)

/** Builds the fixed entries that always exist, whatever is in the library. */
fun standingAlbums(favouriteCount: Int, trashCount: Int, lockedCount: Int): List<Album> = listOf(
    Album(id = -1, name = "Favourites", kind = AlbumKind.FAVOURITES, count = favouriteCount),
    Album(id = -2, name = "Recently deleted", kind = AlbumKind.RECENTLY_DELETED, count = trashCount),
    Album(id = -3, name = "Locked folder", kind = AlbumKind.LOCKED, count = lockedCount),
)

private const val ALBUM_COLUMNS = 2
private const val GAP_DP = 14
private const val PADDING_DP = 18
private const val LABEL_TOP_DP = 8
