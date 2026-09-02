package app.trimgallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import app.trimgallery.core.data.AndroidDatabase
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.Settings
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The library the emulator journeys are run over, in one place.
 *
 * Shared by `GalleryJourneyTest`, which hosts the gallery directly, and by
 * `MainActivityGrantedLaunchTest`, which launches the real Activity over the same library.
 * One definition rather than two, because the two suites exist to cover the same app from
 * different ends and fixtures that drift apart would quietly stop testing the same thing.
 */

/** A tree URI shaped like a picker's, on an authority no refusal rule names. */
internal const val JOURNEY_TREE = "content://app.trimgallery.journey/tree/journey%3ACamera"

/** The clip from `shared/testdata`: real H.264 with a real AAC track. */
internal const val GOLDEN_CLIP = "golden-h264-640x360-3s.mp4"

/** As `GrantedFolders.grants()` builds it: the tree URI is both the id and the ref. */
internal fun journeyGrant() = FolderGrant(
    id = JOURNEY_TREE,
    platformRef = MediaRef(JOURNEY_TREE),
    mode = FolderMode.KEEP,
    displayName = "Journey",
)

internal fun journeyPhoto(context: Context) = journeyItem(
    id = "journey-photo",
    file = writeJpeg(context, "journey.jpg"),
    kind = MediaKind.PHOTO,
    mime = "image/jpeg",
    mtime = 2_000L,
)

internal fun journeyVideo(context: Context) = journeyItem(
    id = "journey-video",
    file = copyOutOfAssets(context, GOLDEN_CLIP),
    kind = MediaKind.VIDEO,
    mime = "video/mp4",
    mtime = 1_000L,
)

@Suppress("LongParameterList")
private fun journeyItem(id: String, file: File, kind: MediaKind, mime: String, mtime: Long) = MediaItem(
    id = id,
    // A file URI, not a SAF document URI, and that is the one seam: without a persisted
    // permission there is no document to address. Everything that reads it — the container
    // reader, the encoder's extractor — goes through the same ContentResolver either way.
    platformRef = MediaRef(Uri.fromFile(file).toString()),
    name = file.name,
    kind = kind,
    codec = null,
    width = 0,
    height = 0,
    fps = null,
    bitrate = null,
    size = file.length(),
    duration = null,
    takenAt = null,
    location = null,
    cameraModel = null,
    phash = null,
    sha256 = null,
    status = MediaStatus.NEW,
    mtime = mtime,
    folderGrantId = JOURNEY_TREE,
    mime = mime,
)

/** A real photograph, so the viewer has something to decode rather than a missing path. */
internal fun writeJpeg(context: Context, name: String): File {
    val bitmap = Bitmap.createBitmap(PHOTO_PX, PHOTO_PX, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.CYAN)
    return File(context.cacheDir, name).apply {
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
    }
}

internal fun copyOutOfAssets(context: Context, name: String): File = File(context.cacheDir, name).apply {
    outputStream().use { out ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.copyTo(out) }
    }
}

/**
 * A repository over the production driver, held in memory.
 *
 * `name = null`: this driver and this callback, so `PRAGMA foreign_keys = ON` is in force
 * exactly as it is on a phone — the crash loop was a foreign key that the JVM test driver
 * leaves off, and a journey test on a permissive database would certify it a second time.
 * In memory so a run leaves nothing behind and starts from nothing.
 */
internal fun inMemoryRepository(context: Context): TrimRepository {
    var minted = 0
    return TrimRepository(
        db = AndroidDatabase.create(context, name = null),
        io = Dispatchers.IO,
        newId = { "journey-${minted++}" },
        nowMs = System::currentTimeMillis,
        readSettings = { Settings() },
        readTier = { Tier.FREE },
        monthStartMs = { 0L },
    )
}

private const val PHOTO_PX = 64
private const val JPEG_QUALITY = 90

// ------------------------------------------------------------- a library over content://

/**
 * The authority `JourneyDocumentsProvider` answers on, declared in the test manifest.
 *
 * Every item below is addressed through it, so that the app reads the way it reads on a
 * phone: through `ContentResolver`, off a provider, with none of the shortcuts a `file://`
 * path allows.
 */
internal const val DOCUMENTS_AUTHORITY = "app.trimgallery.journey.documents"

/** A document URI shaped like SAF's: the tree, then the document under it. */
internal fun documentUri(name: String): Uri =
    Uri.parse("content://$DOCUMENTS_AUTHORITY/tree/journey%3ACamera/document/journey%3ACamera%2F$name")

/**
 * A photograph the size a phone camera writes, served over `content://`.
 *
 * `PHOTO_PX` is 64. That is fine for asserting that a tile exists and wrong for asserting
 * that the viewer survives opening it: a real photograph is eight to fifty megapixels, and
 * the decode, the bitmap, the memory and the transition all scale with it. This one is
 * eight megapixels — the smallest size that is honestly "a photo from a phone".
 *
 * `width` and `height` are zero, as `SafStorage.scan` leaves them: it reads one cursor per
 * folder and never opens the file, so on a first run every item is undated *and* unsized
 * until the container reader gets to it. A test that filled these in would be testing a
 * library the app never actually sees.
 */
internal fun documentPhoto(context: Context, index: Int): MediaItem {
    val name = "camera-$index.jpg"
    writeCameraSizedJpeg(context, name, seed = index)
    return documentItem(
        id = "document-photo-$index",
        name = name,
        kind = MediaKind.PHOTO,
        mime = "image/jpeg",
        size = File(context.cacheDir, name).length(),
        mtime = 10_000L + index,
    )
}

/** The golden clip under a camera-like name, served over `content://`. */
internal fun documentVideo(context: Context, index: Int): MediaItem {
    val name = "camera-$index.mp4"
    val source = copyOutOfAssets(context, GOLDEN_CLIP)
    val copy = File(context.cacheDir, name).apply { source.copyTo(this, overwrite = true) }
    return documentItem(
        id = "document-video-$index",
        name = name,
        kind = MediaKind.VIDEO,
        mime = "video/mp4",
        size = copy.length(),
        mtime = 20_000L + index,
    )
}

@Suppress("LongParameterList")
private fun documentItem(id: String, name: String, kind: MediaKind, mime: String, size: Long, mtime: Long) = MediaItem(
    id = id,
    platformRef = MediaRef(documentUri(name).toString()),
    name = name,
    kind = kind,
    codec = null,
    width = 0,
    height = 0,
    fps = null,
    bitrate = null,
    size = size,
    duration = null,
    takenAt = null,
    location = null,
    cameraModel = null,
    phash = null,
    sha256 = null,
    status = MediaStatus.NEW,
    mtime = mtime,
    folderGrantId = JOURNEY_TREE,
    mime = mime,
)

/**
 * An 8-megapixel JPEG with a picture in it.
 *
 * RGB_565 rather than ARGB_8888 so the bitmap being encoded is 16 MB rather than 32 in the
 * test process, and recycled as soon as it is written. A gradient plus a few blocks, so
 * that the encoder produces a file of realistic size and a decode has real work to do —
 * a flat colour compresses to almost nothing and decodes in a blink.
 */
private fun writeCameraSizedJpeg(context: Context, name: String, seed: Int): File {
    val bitmap = Bitmap.createBitmap(CAMERA_W, CAMERA_H, Bitmap.Config.RGB_565)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint()
    val band = CAMERA_H / BANDS
    for (i in 0 until BANDS) {
        paint.color = Color.rgb((i * 23 + seed * 41) % 256, (i * 71 + seed * 13) % 256, (i * 131) % 256)
        canvas.drawRect(0f, (i * band).toFloat(), CAMERA_W.toFloat(), ((i + 1) * band).toFloat(), paint)
    }
    return File(context.cacheDir, name).apply {
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
    }
}

private const val CAMERA_W = 3264
private const val CAMERA_H = 2448
private const val BANDS = 24
