package app.trimgallery.engine.android

import app.trimgallery.core.model.MediaRef

/**
 * What a cached video frame is called on disk.
 *
 * Its own object, and not a method on [VideoThumbnails], for one reason: everything else in
 * that class needs a `ContentResolver` and a real video, so it can only be checked on a
 * device — while this is the part whose failures are silent. A key that collides draws one
 * video's frame on another's tile with nothing on screen to say so; a key that is too
 * specific caches nothing and the grid extracts frames forever. Both deserve a test that
 * runs on every push.
 */
internal object VideoThumbnailKey {

    /**
     * @param mtime in the key rather than checked against the file: an edited video is a
     *   different picture, and a key that ignored it would show the old frame until the
     *   cache was cleared.
     * @param sizePx in it too, because a frame asked for at grid size and at viewer size
     *   are different pictures.
     */
    fun of(ref: MediaRef, mtime: Long, sizePx: Int): String =
        "${ref.value.hashCode().toUInt().toString(HEX_RADIX)}-$mtime-$sizePx.jpg"

    private const val HEX_RADIX = 16
}
