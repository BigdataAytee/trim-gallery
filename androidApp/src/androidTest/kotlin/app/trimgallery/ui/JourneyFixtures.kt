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
    // permission there is no document to address. Everything that reads it — Coil, the
    // frame extractor, ExoPlayer — goes through the same ContentResolver either way.
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
