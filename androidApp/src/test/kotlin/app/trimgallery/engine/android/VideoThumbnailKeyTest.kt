package app.trimgallery.engine.android

import app.trimgallery.core.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The cache key, which is the whole correctness of the disk cache.
 *
 * A key that collides shows one video's frame on another's tile; a key that is too specific
 * caches nothing and the grid extracts frames forever. Both are silent, and neither shows
 * up on a device until somebody notices a picture that is not theirs.
 *
 * The extraction itself needs a `ContentResolver` and a real video, so it is not tested
 * here — the key is deliberately split out as the part that can be.
 */
class VideoThumbnailKeyTest {

    @Test
    fun theSameFileAtTheSameSizeIsTheSameKey() {
        assertEquals(
            VideoThumbnailKey.of(CAMERA, mtime = 1_000, sizePx = 256),
            VideoThumbnailKey.of(CAMERA, mtime = 1_000, sizePx = 256),
        )
    }

    @Test
    fun anEditedFileIsADifferentPicture() {
        // The mtime is in the key rather than checked against the file, so an edited video
        // re-thumbnails instead of showing the frame it had before the edit.
        assertNotEquals(
            VideoThumbnailKey.of(CAMERA, mtime = 1_000, sizePx = 256),
            VideoThumbnailKey.of(CAMERA, mtime = 2_000, sizePx = 256),
        )
    }

    @Test
    fun aDifferentSizeIsADifferentPicture() {
        assertNotEquals(
            VideoThumbnailKey.of(CAMERA, mtime = 1_000, sizePx = 256),
            VideoThumbnailKey.of(CAMERA, mtime = 1_000, sizePx = 512),
        )
    }

    @Test
    fun twoFilesDoNotShareAKey() {
        // The failure this guards is the ugly one: one video's frame drawn on another's
        // tile, with nothing on screen to say so.
        assertNotEquals(
            VideoThumbnailKey.of(CAMERA, mtime = 1_000, sizePx = 256),
            VideoThumbnailKey.of(SCREENSHOTS, mtime = 1_000, sizePx = 256),
        )
    }

    @Test
    fun theKeyIsAUsableFileName() {
        val key = VideoThumbnailKey.of(CAMERA, mtime = 1_000, sizePx = 256)

        // A tree URI is full of slashes and percent signs; the key must survive being a
        // file name in the cache directory.
        assertTrue(key.none { it in "/\\:%?*\"<>|" }, key)
        assertTrue(key.endsWith(".jpg"), key)
    }

    private companion object {
        val CAMERA = MediaRef("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera")
        val SCREENSHOTS = MediaRef("content://com.android.externalstorage.documents/tree/primary%3APictures")
    }
}
