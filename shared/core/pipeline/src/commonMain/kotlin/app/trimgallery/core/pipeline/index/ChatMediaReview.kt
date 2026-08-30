package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.MediaItem
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The chat-media half of Cleanup (BUILD.md § 8, USER_JOURNEY.md § 9).
 *
 * > **Chat media review:** for granted WhatsApp/Telegram folders, list media by age and
 * > whether it has been opened; bulk-delete to undo bin.
 *
 * This is the feature with the best ratio of space freed to risk taken — a two-year-old
 * forwarded video nobody has opened is the easiest gigabyte in the library — and also the
 * one where a wrong suggestion stings most, because a photograph someone sent you may be
 * the only copy in existence. So nothing here deletes: it sorts, groups and explains, and
 * whatever the user confirms goes to the undo bin like every other removal.
 */
object ChatMediaReview {

    /**
     * Folder-path fragments that identify a messaging app's media directory.
     *
     * Matched against the path rather than guessed from the file, because there is nothing
     * in a JPEG that says it arrived over WhatsApp. BUILD.md § 8 names WhatsApp and Telegram;
     * the others are here because they use the same convention and a user who granted the
     * folder plainly meant it to be included.
     */
    val CHAT_FOLDERS = mapOf(
        "whatsapp" to "WhatsApp",
        "telegram" to "Telegram",
        "signal" to "Signal",
        "viber" to "Viber",
        "wechat" to "WeChat",
        "messenger" to "Messenger",
    )

    /** How old something must be before it is offered. */
    val OLD = 90.days
    val VERY_OLD = 365.days

    /** One bucket of the review screen. */
    data class Bucket(
        val app: String,
        val label: String,
        val items: List<MediaItem>,
    ) {
        val bytes: Long get() = items.sumOf { it.size }
    }

    /** Which messaging app a path belongs to, or null if it is not chat media. */
    fun appFor(path: String?): String? {
        if (path == null) return null
        val lower = path.lowercase()
        return CHAT_FOLDERS.entries.firstOrNull { lower.contains(it.key) }?.value
    }

    /**
     * The review screen: oldest and largest first, grouped by app and age.
     *
     * @param paths the folder path of each item, keyed by id. Supplied rather than read off
     *   `MediaItem`, because a SAF grant may have no filesystem path at all and the caller
     *   is the only thing that knows what it resolved to.
     * @param opened ids the user has actually looked at. Never inferred: "not opened" is
     *   the strongest reason to offer something for deletion, and inferring it from a
     *   missing thumbnail or an access time the filesystem may not keep would offer up
     *   photographs the user looks at often.
     */
    fun review(
        items: List<MediaItem>,
        paths: Map<String, String>,
        opened: Set<String>,
        now: Instant,
    ): List<Bucket> {
        val candidates = items.filter { !it.hidden && !it.favourite }

        return candidates
            .mapNotNull { item ->
                val app = appFor(paths[item.id]) ?: return@mapNotNull null
                val age = age(item, now)
                val label = when {
                    item.id in opened -> null                       // seen; not our business
                    age >= VERY_OLD -> "Over a year old, never opened"
                    age >= OLD -> "Over three months old, never opened"
                    else -> null                                    // too recent to suggest
                }
                label?.let { Triple(app, it, item) }
            }
            .groupBy { it.first to it.second }
            .map { (key, entries) ->
                Bucket(
                    app = key.first,
                    label = key.second,
                    // Largest first: the screen exists to free space, and the user scrolls
                    // from the top.
                    items = entries.map { it.third }.sortedByDescending { it.size },
                )
            }
            .sortedByDescending { it.bytes }
    }

    /** What accepting every bucket would free. */
    fun totalReclaimable(buckets: List<Bucket>): Long = buckets.sumOf { it.bytes }

    /**
     * A favourite is never offered, and neither is anything hidden.
     *
     * The user has already said one matters; the other is behind a biometric prompt and has
     * no business appearing on a cleanup screen at all.
     */
    private fun age(item: MediaItem, now: Instant) =
        now - (item.takenAt ?: Instant.fromEpochMilliseconds(item.mtime))
}
