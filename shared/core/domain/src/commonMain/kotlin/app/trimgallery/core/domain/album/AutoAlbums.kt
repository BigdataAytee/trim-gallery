package app.trimgallery.core.domain.album

import app.trimgallery.core.model.AutoAlbum
import app.trimgallery.core.model.Label
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind

/**
 * Which auto-albums an item belongs to (BUILD.md § 7: "Image labels → search and
 * auto-albums (screenshots, selfies, documents, videos, chat media)").
 *
 * Rules over the index rather than stored membership: an album is then always consistent
 * with what the indexer last saw, costs nothing to keep in step, and cannot rot when an
 * item is re-indexed after being edited.
 *
 * Pure, so every rule is unit tested. These decide what the user sees in a screen they
 * did not curate, so a wrong rule is a wrong-looking library.
 */
object AutoAlbums {

    /**
     * Filenames a screenshot is saved under, across the OEMs BUILD.md cares about.
     *
     * Filename first, label second: a screenshot of a photograph will be labelled as
     * whatever it depicts, and the label would win.
     */
    private val SCREENSHOT_NAME_HINTS = listOf("screenshot", "screen_shot", "screencap", "screen-")

    /** Folders every major chat app writes into (BUILD.md § 8, "Chat media review"). */
    private val CHAT_PATH_HINTS = listOf(
        "/whatsapp/", "/telegram/", "/signal/", "/messenger/", "/viber/", "/wechat/", "/line/",
    )

    private val DOCUMENT_LABELS = setOf("document", "text", "paper", "receipt", "menu", "whiteboard", "book")
    private val SELFIE_LABELS = setOf("selfie", "portrait")

    /** Confidence below which a label is not worth filing an item under. */
    const val MIN_LABEL_CONFIDENCE = 0.7f

    /**
     * @param facingCamera true when the capture metadata says the front camera took it.
     *   The reliable signal for a selfie; labels alone put every portrait in there.
     */
    fun albumsFor(
        item: MediaItem,
        labels: List<Label> = emptyList(),
        path: String? = null,
        facingCamera: Boolean = false,
    ): Set<AutoAlbum> {
        // The locked folder is excluded from every other view, auto-albums included.
        if (item.locked) return emptySet()

        val result = mutableSetOf<AutoAlbum>()
        val confident = labels.filter { it.confidence >= MIN_LABEL_CONFIDENCE }.map { it.text.lowercase() }
        val name = item.name.lowercase()
        val location = path?.lowercase()

        if (item.kind == MediaKind.VIDEO) result += AutoAlbum.VIDEOS

        if (SCREENSHOT_NAME_HINTS.any { name.contains(it) } || confident.contains("screenshot")) {
            result += AutoAlbum.SCREENSHOTS
        }

        if (location != null && CHAT_PATH_HINTS.any { location.contains(it) }) {
            result += AutoAlbum.CHAT_MEDIA
        }

        // A screenshot of a receipt is a screenshot, not a document: the user is looking
        // for the thing they photographed, and filing it in both makes Documents useless.
        if (AutoAlbum.SCREENSHOTS !in result && confident.any { it in DOCUMENT_LABELS }) {
            result += AutoAlbum.DOCUMENTS
        }

        if (item.kind == MediaKind.PHOTO && (facingCamera || confident.any { it in SELFIE_LABELS })) {
            result += AutoAlbum.SELFIES
        }

        return result
    }

    /**
     * Groups a library into its auto-albums, dropping any that would be empty.
     *
     * An album with nothing in it is a dead end; the screen shows only what has content.
     */
    fun build(
        items: List<MediaItem>,
        labelsFor: (MediaItem) -> List<Label> = { emptyList() },
        pathFor: (MediaItem) -> String? = { null },
        facingCameraFor: (MediaItem) -> Boolean = { false },
    ): Map<AutoAlbum, List<MediaItem>> {
        val buckets = linkedMapOf<AutoAlbum, MutableList<MediaItem>>()
        for (item in items) {
            for (album in albumsFor(item, labelsFor(item), pathFor(item), facingCameraFor(item))) {
                buckets.getOrPut(album) { mutableListOf() } += item
            }
        }
        // Stable order, so the Albums screen does not reshuffle between runs.
        return AutoAlbum.entries
            .mapNotNull { album -> buckets[album]?.let { album to it.toList() } }
            .toMap()
    }
}
