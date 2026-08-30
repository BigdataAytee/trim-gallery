package app.trimgallery.core.domain.skip

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.SkipReason

/**
 * The Skipped screen (BUILD.md § 9: *"Skipped list with reasons"*).
 *
 * Every file the app declines to touch is shown to the user with a reason, and this is
 * where those reasons are written. That makes it product surface, not plumbing: the
 * difference between *"HDR video isn't supported yet"* and *"failed"* is the difference
 * between a user who trusts the app and one who does not.
 *
 * The rules it holds:
 *
 * - **A reason is a sentence, not an enum name.** DESIGN_SYSTEM.md § Copy tone: short,
 *   calm, concrete; numbers over adjectives; never "compress" or "shrink" — "optimise",
 *   "freed", "smaller".
 * - **"Try again" appears only where trying again could work.** USER_JOURNEY.md § 14 puts
 *   it on failures. Offering it on a Motion Photo would be a button that does nothing, and
 *   a user who presses it twice learns not to believe the rest of the screen.
 * - **Nothing here is an apology.** A file that is already efficient is good news.
 */
object SkipList {

    /** One row on the Skipped screen. */
    data class Row(
        val item: MediaItem,
        val reason: SkipReason?,
        val explanation: String,
        val retryable: Boolean,
    )

    /** A group of rows sharing a reason, for a screen that would otherwise be a long list. */
    data class Group(
        val reason: SkipReason?,
        val heading: String,
        val explanation: String,
        val items: List<MediaItem>,
        val retryable: Boolean,
    ) {
        val count: Int get() = items.size
    }

    /**
     * Why a file was not optimised, in the user's words.
     *
     * A null reason means the item failed rather than being skipped — the pipeline records
     * a `SkipReason` only for a deliberate decision, and "something went wrong" is not one.
     */
    fun explain(reason: SkipReason?): String = when (reason) {
        null -> "Something went wrong. You can try this one again."
        SkipReason.ALREADY_EFFICIENT -> "Already as small as it usefully gets."
        SkipReason.TOO_SMALL -> "Too small to be worth optimising."
        SkipReason.WOULD_NOT_SHRINK -> "Optimising this wouldn't free enough to be worth it."
        SkipReason.HDR -> "HDR video is left untouched — re-encoding it would lose the extra range."
        SkipReason.MOTION_PHOTO -> "Motion photos carry a short video inside them, which optimising would drop."
        SkipReason.ULTRA_HDR -> "Ultra HDR photos carry a brightness map, which optimising would drop."
        SkipReason.LIVE_PHOTO -> "Live photos carry a paired video, which optimising would drop."
        SkipReason.RAW -> "RAW files hold the sensor's own data. Trim leaves them exactly as they are."
        SkipReason.IN_CLOUD_ONLY -> "This one isn't on the phone yet. Download it and Trim will pick it up."
        SkipReason.UNSUPPORTED_CODEC -> "Trim doesn't recognise this format."
        SkipReason.NO_HARDWARE_ENCODER -> "This phone's encoder can't handle a file this size."
        SkipReason.COULD_NOT_REACH_QUALITY -> "Trim couldn't make this smaller without a visible difference."
    }

    /** A short heading for the group, for the row above the thumbnails. */
    fun heading(reason: SkipReason?): String = when (reason) {
        null -> "Failed"
        SkipReason.ALREADY_EFFICIENT -> "Already efficient"
        SkipReason.TOO_SMALL -> "Too small"
        SkipReason.WOULD_NOT_SHRINK -> "Not worth it"
        SkipReason.HDR -> "HDR video"
        SkipReason.MOTION_PHOTO -> "Motion photos"
        SkipReason.ULTRA_HDR -> "Ultra HDR"
        SkipReason.LIVE_PHOTO -> "Live photos"
        SkipReason.RAW -> "RAW"
        SkipReason.IN_CLOUD_ONLY -> "Not downloaded"
        SkipReason.UNSUPPORTED_CODEC -> "Unsupported format"
        SkipReason.NO_HARDWARE_ENCODER -> "Too big for this phone"
        SkipReason.COULD_NOT_REACH_QUALITY -> "Couldn't keep the quality"
    }

    /**
     * Whether "Try again" does anything.
     *
     * Only two things can change: the file can arrive from the cloud, and a failure can be
     * transient. Everything else is a property of the file or of the phone, and would give
     * the same answer tonight, tomorrow and next year.
     *
     * `COULD_NOT_REACH_QUALITY` is deliberately not retryable: BUILD.md § 5 says such a
     * file is *"skipped permanently"* after two step-ups, and the search is deterministic —
     * a second run would spend three more encodes reaching the same conclusion.
     */
    fun isRetryable(reason: SkipReason?): Boolean = when (reason) {
        null -> true                              // a failure
        SkipReason.IN_CLOUD_ONLY -> true
        else -> false
    }

    /**
     * The rows for one item.
     *
     * `SKIPPED` and `FAILED` both land on this screen. USER_JOURNEY.md § 14 is explicit
     * that a failed file is *"listed under Skipped with reason and 'Try again'"* — the user
     * does not care which of our internal states it is in, only that it was not done.
     */
    fun row(item: MediaItem): Row? {
        if (item.status != MediaStatus.SKIPPED && item.status != MediaStatus.FAILED) return null
        val reason = item.skipReason.takeIf { item.status == MediaStatus.SKIPPED }
        return Row(
            item = item,
            reason = reason,
            explanation = explain(reason),
            retryable = isRetryable(reason),
        )
    }

    /**
     * The whole screen: grouped by reason, retryable groups first, then largest first.
     *
     * Retryable first because those are the only rows the user can act on, and a screen
     * that buries its one actionable item under four hundred already-efficient photos is a
     * screen nobody scrolls. Within each half, the biggest group leads: it is both the most
     * informative and the most likely to explain a number the user came here to question.
     */
    fun groups(items: List<MediaItem>): List<Group> =
        items.mapNotNull(::row)
            .groupBy { it.reason }
            .map { (reason, rows) ->
                Group(
                    reason = reason,
                    heading = heading(reason),
                    explanation = explain(reason),
                    items = rows.map { it.item },
                    retryable = isRetryable(reason),
                )
            }
            .sortedWith(
                compareByDescending<Group> { it.retryable }
                    .thenByDescending { it.count }
                    .thenBy { it.heading },
            )

    /**
     * Items a "Try again" would actually re-queue.
     *
     * Returned rather than acted on, because putting them back is a database write and this
     * object does not do those.
     */
    fun retryable(items: List<MediaItem>): List<MediaItem> =
        items.mapNotNull(::row).filter { it.retryable }.map { it.item }

    /**
     * What the Space screen says when there is nothing to do at all.
     *
     * USER_JOURNEY.md § 14, verbatim — the one line in this file that is quoted from the
     * spec rather than written to it.
     */
    const val NOTHING_TO_DO = "Everything's already efficient — nothing to do tonight."
}
