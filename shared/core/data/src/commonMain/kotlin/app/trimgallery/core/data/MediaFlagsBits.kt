package app.trimgallery.core.data

import app.trimgallery.core.model.MediaFlags

/**
 * `media_item.flags`, the bitmask SCHEMA.md specifies.
 *
 * A bitmask in the database and a named struct in Kotlin: the column has to be one integer
 * so a single index can serve every flag query, and the code has to name them so nobody
 * has to remember that 128 means hidden. This is the only place the two meet.
 */
object MediaFlagsBits {
    const val HDR = 1L
    const val MOTION_PHOTO = 2L
    const val ULTRA_HDR = 4L
    const val LIVE_PHOTO = 8L
    const val RAW = 16L
    const val IN_CLOUD_ONLY = 32L
    const val FAVOURITE = 64L
    const val HIDDEN = 128L

    fun decode(bits: Long) = MediaFlags(
        hdr = bits and HDR != 0L,
        motionPhoto = bits and MOTION_PHOTO != 0L,
        ultraHdr = bits and ULTRA_HDR != 0L,
        livePhoto = bits and LIVE_PHOTO != 0L,
        raw = bits and RAW != 0L,
        inCloudOnly = bits and IN_CLOUD_ONLY != 0L,
        favourite = bits and FAVOURITE != 0L,
        hidden = bits and HIDDEN != 0L,
    )

    fun encode(flags: MediaFlags): Long =
        (if (flags.hdr) HDR else 0L) or
            (if (flags.motionPhoto) MOTION_PHOTO else 0L) or
            (if (flags.ultraHdr) ULTRA_HDR else 0L) or
            (if (flags.livePhoto) LIVE_PHOTO else 0L) or
            (if (flags.raw) RAW else 0L) or
            (if (flags.inCloudOnly) IN_CLOUD_ONLY else 0L) or
            (if (flags.favourite) FAVOURITE else 0L) or
            (if (flags.hidden) HIDDEN else 0L)
}
