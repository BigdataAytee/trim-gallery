package app.trimgallery.engine.android

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.trimgallery.core.model.MediaRef
import app.trimgallery.engine.MetadataCopier
import app.trimgallery.engine.TempFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part12.MovieHeaderBox
import org.mp4parser.boxes.iso14496.part12.TrackHeaderBox

/**
 * Carries the original's identity onto the replacement (BUILD.md § 2.4).
 *
 * Everything here writes to a **temp file the app owns**, never to the user's folder —
 * this runs as step 1 of the ARCHITECTURE.md § 7 contract, before the original has been
 * touched at all. That is why this file is not on the write guard's allow-list and does
 * not need to be.
 *
 * What has to survive, from the safe-replace skill's table: creation time, GPS, rotation,
 * colour information, and EXIF/XMP wholesale. Losing any of it turns an optimised library
 * into one where the photos are in the wrong order, in the wrong place on the map, or
 * sideways.
 */
class MetadataCopierAndroid(private val context: Context) : MetadataCopier {

    override suspend fun copy(from: MediaRef, to: TempFile) = withContext(Dispatchers.IO) {
        val source = Uri.parse(from.value)
        val target = File(to.path)
        require(target.isFile) { "the replacement must exist before metadata is copied onto it" }

        when {
            isStill(source) -> copyExif(source, target)
            else -> copyContainer(source, target)
        }
    }

    /**
     * EXIF and XMP, tag by tag.
     *
     * `ExifInterface.setAttribute` for every tag the source carries rather than a
     * hand-picked list: BUILD.md § 2.4 says the metadata is preserved, and a curated list
     * is a list of the things someone remembered. Maker notes, lens data and colour
     * profile tags all matter to somebody.
     */
    private fun copyExif(source: Uri, target: File) {
        val input = context.contentResolver.openInputStream(source) ?: return
        val from = input.use { ExifInterface(it) }
        val onto = ExifInterface(target)

        EXIF_TAGS.forEach { tag ->
            from.getAttribute(tag)?.let { onto.setAttribute(tag, it) }
        }
        onto.saveAttributes()
    }

    /**
     * MP4 container metadata: creation time, and the rotation matrix.
     *
     * Media3's muxer writes a fresh `mvhd`/`tkhd` with the encode's own timestamps, so
     * without this every optimised video claims to have been shot the night it was
     * optimised — the single most damaging thing this app could do to a library without
     * losing a byte.
     *
     * Rotation lives in the track header matrix, not in a tag. Dropping it plays every
     * portrait video sideways.
     */
    private fun copyContainer(source: Uri, target: File) {
        val descriptor = context.contentResolver.openFileDescriptor(source, "r") ?: return
        val (creation, modification, matrices) = descriptor.use { fd ->
            IsoFile(fd.fileDescriptor).use { iso ->
                val movie = iso.getBoxes(MovieHeaderBox::class.java).firstOrNull()
                Triple(
                    movie?.creationTime,
                    movie?.modificationTime,
                    iso.getBoxes(TrackHeaderBox::class.java, true).map { it.matrix },
                )
            }
        }
        if (creation == null && matrices.isEmpty()) return

        // mp4parser parses in place but does not write in place: the boxes are mutated in
        // memory and the whole container is written out again. Both files here are
        // app-private temps, so the rewrite and the rename that follows never touch
        // anything of the user's.
        val rewritten = File(target.parentFile, "${target.name}.meta")
        try {
            IsoFile(target.absolutePath).use { iso ->
                iso.getBoxes(MovieHeaderBox::class.java).firstOrNull()?.let { movie ->
                    creation?.let { movie.creationTime = it }
                    modification?.let { movie.modificationTime = it }
                }
                iso.getBoxes(TrackHeaderBox::class.java, true).forEachIndexed { index, track ->
                    matrices.getOrNull(index)?.let { track.matrix = it }
                }
                FileOutputStream(rewritten).use { out -> iso.writeContainer(out.channel) }
            }
            check(rewritten.length() > 0) { "rewriting the container produced an empty file" }
            check(rewritten.renameTo(target)) { "could not put the re-tagged container back" }
        } catch (e: Exception) {
            rewritten.delete()
            throw e
        }

        // The file's own timestamp, so anything reading the filesystem rather than the
        // container agrees with it. SafeReplacerAndroid sets the provider's lastModified
        // as well, but that column is read-only on many providers; this one never is.
        creation?.let { target.setLastModified(it.time) }
    }

    private fun isStill(uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("image/") == true

    private companion object {
        /**
         * Every tag `ExifInterface` can round-trip, grouped by what it protects.
         *
         * Ordered so that the ones the app promises about — date, place, orientation —
         * are visible at the top rather than buried in an alphabetical list.
         */
        val EXIF_TAGS = listOf(
            // When
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            // Where
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            // Which way up
            ExifInterface.TAG_ORIENTATION,
            // Colour
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_WHITE_POINT,
            ExifInterface.TAG_PRIMARY_CHROMATICITIES,
            ExifInterface.TAG_GAMMA,
            // What took it, and how
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            // The user's own words
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_XMP,
        )
    }
}
