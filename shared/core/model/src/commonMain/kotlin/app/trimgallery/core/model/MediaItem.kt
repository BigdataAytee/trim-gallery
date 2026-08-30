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
    /** Marked by the user. SCHEMA.md `media_item.flags` bit 64. */
    val favourite: Boolean = false,
    /**
     * In the locked folder. SCHEMA.md `media_item.flags` bit 128.
     *
     * Hidden items are excluded from every other view — grid, albums, search, people —
     * so this has to be readable without a join, which is why SCHEMA.md indexes `flags`.
     */
    val hidden: Boolean = false,
)

/** A point on the earth, as recorded by the camera. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * ARCHITECTURE.md § 4 and SCHEMA.md `media_item`. One row per file in the user's library.
 */
data class MediaItem(
    val id: String,
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
    /** Which grant this was found under; decides the folder mode and offload target. */
    val folderGrantId: String? = null,
    val mime: String? = null,
    /**
     * Bytes triage thinks this file could give back (SCHEMA.md `est_saving`).
     *
     * The queue's ordering key: BUILD.md § 6 works largest-saving-first so an interrupted
     * night still delivers most of the space it was going to.
     */
    val estSaving: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /**
     * When this app last replaced this file, or null if it never has.
     *
     * A supplement to SCHEMA.md (recorded in PROJECT.md), and the only reliable defence
     * against **generational loss**: every re-encode targets VMAF 95 against whatever it
     * is given, so optimising our own output measures quality against an already-lossy
     * copy. Two nights of that is visible.
     *
     * The primary defence is that the pipeline writes the new size and mtime back after a
     * replace, so `LibraryDiff` sees no change and never resets the item to `NEW`. But a
     * provider that rounds a timestamp, or a media scan that rewrites one, would defeat
     * that silently — and the failure is a photograph the user cannot get back. This
     * column makes the rule a property of the row instead.
     */
    val optimisedAt: Long? = null,
) {
    /** Pixels per frame; the search cares about this more than the label "4K". */
    val pixels: Long get() = width.toLong() * height.toLong()

    /** SCHEMA.md keeps these in the `flags` bitmask; call sites read them by name. */
    val favourite: Boolean get() = flags.favourite
    val hidden: Boolean get() = flags.hidden
}
