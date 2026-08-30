package app.trimgallery.core.model

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * Opaque handle to an item in the user's library.
 *
 * Android holds a SAF document URI, iOS a `PHAsset` localIdentifier (ARCHITECTURE.md
 * § 4, `MediaItem.platformRef`). Shared code never parses it — only the platform
 * `LibraryStorage` and `Replacer` know what is inside.
 */
@JvmInline
value class MediaRef(val value: String)

enum class MediaKind { VIDEO, PHOTO, PNG, FILE }

/** Where an item is in its lifecycle (ARCHITECTURE.md § 9). */
enum class MediaStatus { NEW, INDEXED, CANDIDATE, PROCESSING, DONE, SKIPPED, FAILED }

/**
 * Why an item will never be optimised. Every skip is shown to the user with its reason
 * (BUILD.md § 9, "Skipped list"), so these are user-facing and must stay honest.
 */
enum class SkipReason {
    ALREADY_EFFICIENT,
    TOO_SMALL,
    HDR,
    MOTION_PHOTO,
    ULTRA_HDR,
    LIVE_PHOTO,
    RAW,
    IN_CLOUD_ONLY,
    UNSUPPORTED_CODEC,
    NO_HARDWARE_ENCODER,
    WOULD_NOT_SHRINK,
    COULD_NOT_REACH_QUALITY,
}

/**
 * Format traits that decide whether an item may be touched at all.
 *
 * BUILD.md § 2.5 skips HDR video, Motion Photos, Ultra HDR JPEGs and RAW in v1: the
 * metrics are not calibrated for them and re-encoding destroys embedded data.
 */
data class MediaFlags(
    val hdr: Boolean = false,
    val motionPhoto: Boolean = false,
    val ultraHdr: Boolean = false,
    val livePhoto: Boolean = false,
    val raw: Boolean = false,
    val inCloudOnly: Boolean = false,
)

/** A point on the earth, as recorded by the camera. */
data class GeoPoint(val lat: Double, val lon: Double)

/** ARCHITECTURE.md § 4. One row per file in the user's library. */
data class MediaItem(
    val id: Long,
    val platformRef: MediaRef,
    val name: String,
    val kind: MediaKind,
    val codec: String?,
    val width: Int,
    val height: Int,
    val fps: Double?,
    val bitrate: Long?,
    val size: Long,
    val duration: Long?,
    val takenAt: Instant?,
    val location: GeoPoint?,
    val cameraModel: String?,
    val flags: MediaFlags = MediaFlags(),
    val phash: Long?,
    val sha256: String?,
    val status: MediaStatus = MediaStatus.NEW,
    val skipReason: SkipReason? = null,
    val mtime: Long,
    /**
     * Marked by the user. Not in the ARCHITECTURE.md § 4 schema; added for milestone 8,
     * which requires a Favourites screen, and recorded in PROJECT.md. A column rather
     * than an album because it is a property of the item, survives album deletion, and
     * every gallery the user has ever used treats it that way.
     */
    val favourite: Boolean = false,
    /**
     * In the locked folder. Also an addition to § 4 (see PROJECT.md). Items in the
     * locked folder are excluded from every other view — grid, albums, search, people —
     * so this has to be readable without a join.
     */
    val locked: Boolean = false,
) {
    /** Pixels per frame; the search cares about this more than the label "4K". */
    val pixels: Long get() = width.toLong() * height.toLong()
}
