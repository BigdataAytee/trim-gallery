package app.trimgallery.core.domain.space

import app.trimgallery.core.domain.trash.TrashPolicy
import app.trimgallery.core.model.Job
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import kotlin.time.Instant

/**
 * "See what changed" — the list behind the morning card (USER_JOURNEY.md § 4, § 5).
 *
 * > History list with before/after and Restore … Restore → confirmation sheet showing where
 * > the original is (bin / SD card) … If expired: "The original was removed on <date> after
 * > the 30-day window".
 *
 * The screen exists to make the app's central promise checkable. It is where a user goes
 * when they suspect a photo looks worse, so the two things it must never do are claim a
 * file can be restored when it cannot, and fail to say *where* an original is when it has
 * moved to a card that may not be in the phone.
 */
object History {

    /** Where a restore would take the file back from, in the user's terms. */
    sealed interface Restorable {
        /** The original is on the phone. */
        data class FromBin(val expiresAt: Instant?) : Restorable

        /** The original is on removable storage, which may not be present. */
        data object FromExternal : Restorable

        /**
         * The original is in the system's own Recently Deleted — iOS, and only iOS.
         *
         * PhotoKit has no rename, so a replacement is add-then-delete and the deleted
         * original lands in the OS bin for 30 days. That bin *is* the undo (ARCHITECTURE.md
         * § 6), and it is also the one this app cannot reach: there is no API to restore
         * from Recently Deleted, by design — Apple requires the person to do it themselves.
         *
         * So this is a distinct state rather than a flavour of [FromBin]. A Restore button
         * that opened Photos and hoped would be a button that lies; this one says where the
         * file is, how long it has, and offers to take the user there.
         */
        data class FromSystemBin(val expiresAt: Instant?) : Restorable

        /** Restorable in principle, but the window has closed. */
        data class Expired(val removedAt: Instant?) : Restorable

        /** The user already restored it. */
        data object AlreadyRestored : Restorable

        /** Nothing was kept: the folder is in "Keep originals" and the file was never replaced. */
        data object NotApplicable : Restorable
    }

    data class Row(
        val item: MediaItem,
        val job: Job,
        val originalSize: Long?,
        val newSize: Long?,
        val restorable: Restorable,
        val finishedAt: Instant?,
    ) {
        val saved: Long? get() = job.saved

        /**
         * How much smaller, as a plain multiple.
         *
         * "2.3× smaller" rather than "57% saved": people compare multiples more easily than
         * percentages, and BUILD.md § 14 logs the factor for the same reason.
         */
        val factor: Double?
            get() {
                val before = originalSize ?: return null
                val after = newSize ?: return null
                return if (after > 0) before.toDouble() / after else null
            }

        /** Only an offer the app can actually honour is shown as one. */
        val canRestore: Boolean
            get() = restorable is Restorable.FromBin || restorable is Restorable.FromExternal
    }

    /**
     * Builds the list, newest first.
     *
     * @param undoByMedia the live undo entry per media id, if any.
     *
     * Only jobs that actually succeeded appear. A failed or cancelled job belongs on the
     * Skipped screen with its reason (BUILD.md § 9), not in a history of changes that were
     * made — listing it here would tell the user something changed when nothing did.
     */
    fun rows(
        jobs: List<Job>,
        items: Map<String, MediaItem>,
        undoByMedia: Map<String, UndoEntry>,
        now: Instant,
    ): List<Row> = jobs
        .filter { it.state == JobState.SUCCEEDED }
        .mapNotNull { job ->
            val item = items[job.mediaId] ?: return@mapNotNull null
            Row(
                item = item,
                job = job,
                originalSize = job.originalSize,
                newSize = job.newSize,
                restorable = restorable(undoByMedia[job.mediaId], now),
                finishedAt = job.finishedAt,
            )
        }
        .sortedWith(
            compareByDescending<Row> { it.finishedAt?.toEpochMilliseconds() ?: 0 }.thenBy { it.item.id },
        )

    /**
     * What the Restore button can honestly offer.
     *
     * An expired entry is reported as expired rather than as absent, so the sheet can say
     * *when* the original went (USER_JOURNEY.md § 5) instead of leaving the user to guess
     * whether the app ever kept one.
     */
    fun restorable(entry: UndoEntry?, now: Instant): Restorable {
        if (entry == null) return Restorable.NotApplicable

        return when (entry.state) {
            UndoState.RESTORED -> Restorable.AlreadyRestored
            UndoState.EXPIRED -> Restorable.Expired(entry.expiresAt)
            UndoState.ACTIVE, UndoState.OFFLOADED -> when {
                TrashPolicy.isExpired(entry, now) -> Restorable.Expired(entry.expiresAt)
                // Named separately because the original may simply not be in the phone:
                // offering a one-tap restore for a file on a card in a drawer is a promise
                // the app cannot keep.
                entry.location == UndoLocation.OFFLOAD -> Restorable.FromExternal
                // iOS. Distinct from the app's own bin because this app cannot restore from
                // it — see FromSystemBin. Falling through to FromBin here was the bug this
                // check replaces: it offered a one-tap restore the platform does not have.
                entry.location == UndoLocation.SYSTEM_TRASH -> Restorable.FromSystemBin(entry.expiresAt)
                else -> Restorable.FromBin(entry.expiresAt)
            }
        }
    }

    /**
     * The line on the confirmation sheet.
     *
     * DESIGN_SYSTEM.md § Copy tone: short, calm, concrete, and never "compress". Written to
     * be true rather than reassuring — a file on a card the user has taken out is not
     * "ready to restore", and saying so up front is better than a failure afterwards.
     */
    fun restoreExplanation(restorable: Restorable, formatDate: (Instant) -> String): String =
        when (restorable) {
            is Restorable.FromBin ->
                if (restorable.expiresAt == null) {
                    "The original is on this phone and will be kept until you empty the bin."
                } else {
                    "The original is on this phone until ${formatDate(restorable.expiresAt)}."
                }
            Restorable.FromExternal ->
                "The original is on your external storage. Connect it to restore this file."
            is Restorable.FromSystemBin ->
                if (restorable.expiresAt == null) {
                    "Your original is in Photos, under Recently Deleted. Restore it from there."
                } else {
                    "Your original is in Photos, under Recently Deleted, until " +
                        "${formatDate(restorable.expiresAt)}. Restore it from there."
                }
            is Restorable.Expired ->
                restorable.removedAt?.let { "The original was removed on ${formatDate(it)}." }
                    ?: "The original has already been removed."
            Restorable.AlreadyRestored -> "The original has been restored."
            Restorable.NotApplicable -> "This file was not changed."
        }

    /** The morning card's headline: what one night did. */
    fun freedIn(rows: List<Row>): Long = rows.sumOf { it.saved ?: 0 }

    /**
     * Whether the app can put the original back itself, or only say where it is.
     *
     * The distinction the Restore button is drawn from: one label opens a confirmation and
     * does the work, the other opens an explanation. Getting it wrong on iOS means a button
     * that appears to work and does not.
     */
    fun isOneTap(restorable: Restorable): Boolean = when (restorable) {
        is Restorable.FromBin -> true
        Restorable.FromExternal -> true
        is Restorable.FromSystemBin -> false
        is Restorable.Expired, Restorable.AlreadyRestored, Restorable.NotApplicable -> false
    }

    /**
     * Where "Open Photos" should send the user, when the platform offers anywhere.
     *
     * `photos-redirect://` is the documented scheme for opening the Photos app. It cannot
     * open Recently Deleted directly — there is no deep link for it — so the sheet has to
     * say where to go once they are there, which is why [restoreExplanation] names the
     * album rather than only the app.
     */
    const val PHOTOS_DEEP_LINK = "photos-redirect://"

    /** The button on a state the app cannot act on itself. */
    fun secondaryActionLabel(restorable: Restorable): String? = when (restorable) {
        is Restorable.FromSystemBin -> "Open Photos"
        is Restorable.Expired -> "Change how long originals are kept"
        else -> null
    }
}
