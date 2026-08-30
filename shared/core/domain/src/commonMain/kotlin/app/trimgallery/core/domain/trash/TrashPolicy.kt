package app.trimgallery.core.domain.trash

import app.trimgallery.core.model.FolderMode
import app.trimgallery.core.model.JobState
import app.trimgallery.core.model.UndoEntry
import app.trimgallery.core.model.UndoLocation
import app.trimgallery.core.model.UndoState
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * How long an original stays recoverable, and what the "Recently deleted" screen says
 * about it.
 *
 * This is the machinery behind the only promise that makes the app safe to use:
 * compression is visually lossless, not lossless, so **the original is the undo**
 * (PROJECT.md § Quality and reversibility). Everything here is pure and unit tested,
 * because an off-by-one in an expiry is a deleted photograph.
 */
object TrashPolicy {

    /** BUILD.md § 6: the "Free space" default. */
    const val DEFAULT_RETENTION_DAYS = 30

    /** Below this the screen warns rather than just counting down. */
    const val EXPIRING_SOON_DAYS = 3

    /**
     * When an entry parked now would expire, or null if it never does.
     *
     * Only "Free space" expires. "Keep originals" never does, and an offloaded original
     * lives on the card until the user removes it — neither has a countdown to show.
     */
    fun expiresAt(mode: FolderMode, parkedAt: Instant, retentionDays: Int = DEFAULT_RETENTION_DAYS): Instant? =
        when (mode) {
            FolderMode.FREE -> parkedAt + retentionDays.days
            FolderMode.KEEP, FolderMode.OFFLOAD -> null
        }

    /**
     * Whole days left before [entry] is deleted for good, or null when it never expires.
     *
     * Rounded **up**: an entry with eleven hours left says "1 day", not "0 days". The
     * user should never see a zero next to something they can still save.
     */
    fun daysLeft(entry: UndoEntry, now: Instant): Int? {
        val expiry = entry.expiresAt ?: return null
        if (expiry <= now) return 0
        val remaining = expiry - now
        return remaining.inWholeDays.toInt() +
            if (remaining.inWholeNanoseconds % 1.days.inWholeNanoseconds > 0) 1 else 0
    }

    /** True once the sweep may delete it (ARCHITECTURE.md § 7, `undo.sweep()`). */
    fun isExpired(entry: UndoEntry, now: Instant): Boolean {
        val expiry = entry.expiresAt ?: return false
        return entry.state == UndoState.ACTIVE && expiry <= now
    }

    /**
     * Entries the sweep should remove.
     *
     * Active and past their date — and **only where the job that parked the original
     * actually succeeded**.
     *
     * That last condition is the one this function exists for. An `UndoEntry` is written as
     * the final step of the ARCHITECTURE.md § 7 contract, so its existence normally implies
     * the swap completed. "Normally" is not good enough here: a process killed between the
     * journal write and the job's own status update, a rollback that could not reach the
     * row, a `FAILED` job whose replacement was discarded — each leaves an entry whose
     * original may be *the only copy of that file left*. Sweeping it deletes the user's
     * photograph and reports space freed.
     *
     * So the job's state is a required argument rather than a defaulted one. A default of
     * "assume it succeeded" would put the hole straight back, and it would be invisible:
     * every test would pass, and the failure would only ever appear on somebody's phone
     * thirty days after a night that went wrong.
     *
     * @param jobStateOf the state of the job named by the entry, or null when the row is
     *   gone. Null is treated as *not* succeeded: an entry whose job cannot be found is an
     *   entry nothing can vouch for.
     */
    fun expired(entries: List<UndoEntry>, now: Instant, jobStateOf: (UndoEntry) -> JobState?): List<UndoEntry> =
        entries.filter { isExpired(it, now) && jobStateOf(it) == JobState.SUCCEEDED }

    /**
     * Entries that are due but held back because their job did not succeed.
     *
     * Returned rather than silently skipped, so the condition is observable: an original
     * kept past its window because a night went wrong is a thing an operator should be able
     * to see, and a user should not be told the space was freed.
     */
    fun heldBack(entries: List<UndoEntry>, now: Instant, jobStateOf: (UndoEntry) -> JobState?): List<UndoEntry> =
        entries.filter { isExpired(it, now) && jobStateOf(it) != JobState.SUCCEEDED }

    /**
     * What the row says under the thumbnail.
     *
     * Written to be true rather than reassuring: an offloaded original is on a card that
     * may not be in the phone, and saying "Kept" would imply otherwise.
     */
    fun subtitle(entry: UndoEntry, now: Instant): String = when {
        entry.state == UndoState.RESTORED -> "Restored"
        entry.state == UndoState.EXPIRED -> "Deleted"
        entry.location == UndoLocation.OFFLOAD -> "On external storage"
        entry.location == UndoLocation.BIN && entry.expiresAt == null -> "Kept until you empty the bin"
        else -> when (val left = daysLeft(entry, now)) {
            null -> "Kept"
            0 -> "Deletes today"
            1 -> "1 day left"
            else -> "$left days left"
        }
    }

    /** True when the row should be emphasised. */
    fun isExpiringSoon(entry: UndoEntry, now: Instant): Boolean {
        val left = daysLeft(entry, now) ?: return false
        return entry.state == UndoState.ACTIVE && left <= EXPIRING_SOON_DAYS
    }

    /** Restorable entries, soonest to expire first — the ones the user must act on. */
    fun restorable(entries: List<UndoEntry>, now: Instant): List<UndoEntry> = entries
        .filter { it.state == UndoState.ACTIVE && !isExpired(it, now) }
        .sortedWith(compareBy(nullsLast()) { it.expiresAt })
}
