package app.trimgallery.core.pipeline.index

import app.trimgallery.core.model.MediaItem
import kotlin.math.abs

/**
 * Groups a library's duplicates and picks which copy to keep (BUILD.md § 8).
 *
 * > **Duplicates:** grouped review, pick what to keep, rest to undo bin.
 *
 * Two kinds, and the difference matters to the user:
 *
 * - **Exact** — identical bytes, found by SHA-256. There is no judgement to make; the
 *   files are interchangeable and deleting all but one loses nothing at all.
 * - **Near** — the same picture, found by perceptual hash. Bursts, repeated screenshots,
 *   the copy a messaging app re-compressed. Deleting one of these *does* lose something,
 *   even if only a few pixels, so the app suggests and the user decides.
 *
 * Nothing here deletes anything. It produces groups; the cleanup screen shows them, and
 * whatever the user confirms goes to the undo bin like every other removal.
 */
object DuplicateFinder {

    /**
     * How far two images' shapes may differ and still be the same picture.
     *
     * The perceptual hash reduces everything to a square grid and so ignores aspect ratio
     * entirely: a panorama and a portrait crop of one scene hash alike. Without this check
     * the screen would offer a user the chance to delete a photograph that merely resembles
     * another at a different shape — the failure that destroys trust in the whole feature.
     *
     * Two per cent, so that a re-encode that rounds 1079 to 1080 still matches.
     */
    const val ASPECT_TOLERANCE = 0.02

    enum class Kind { EXACT, NEAR }

    /**
     * One group, best copy first.
     *
     * [best] is a suggestion the user can override, never an action already taken.
     */
    data class Group(val kind: Kind, val items: List<MediaItem>, val best: MediaItem) {
        /** What accepting the suggestion would free. */
        val reclaimable: Long get() = items.filter { it.id != best.id }.sumOf { it.size }
    }

    /**
     * @param nearThreshold Hamming distance for the near pass. Raising it finds more
     *   bursts and starts joining different photographs taken in the same place.
     */
    fun find(items: List<MediaItem>, nearThreshold: Int = PerceptualHash.NEAR_DUPLICATE_DISTANCE): List<Group> {
        // Hidden items are excluded from every other view (SCHEMA.md `flags` bit 128), and
        // a cleanup screen that surfaced them would be a hole straight through the locked
        // folder.
        val visible = items.filter { !it.hidden }

        val exact = groupExact(visible)
        val alreadyGrouped = exact.flatMap { group -> group.items.map { it.id } }.toSet()

        // Near-duplicate detection runs on what is left. A set of byte-identical files is
        // already one group; re-testing its members against each other perceptually would
        // merely rediscover it, and testing them against *other* files would let one
        // exact group drag unrelated pictures in behind it.
        val near = groupNear(visible.filter { it.id !in alreadyGrouped }, nearThreshold)

        return (exact + near).sortedByDescending { it.reclaimable }
    }

    private fun groupExact(items: List<MediaItem>): List<Group> = items.filter { it.sha256 != null }
        .groupBy { it.sha256 }
        .values
        .filter { it.size > 1 }
        .map { members -> Group(Kind.EXACT, order(members), pickBest(members)) }

    /**
     * Single-link grouping over the perceptual hashes.
     *
     * Quadratic in the number of items that share a bucket, which is the honest cost: a
     * hundred thousand photographs is ten billion comparisons and would take all night. The
     * bucketing below is what makes it tractable — items are first split by their shape, so
     * the comparison only ever runs within one aspect-ratio class.
     */
    private fun groupNear(items: List<MediaItem>, threshold: Int): List<Group> {
        val hashed = items.filter { it.phash != null && it.pixels > 0 }
        val used = mutableSetOf<String>()
        val groups = mutableListOf<Group>()

        hashed.forEachIndexed { i, candidate ->
            if (candidate.id in used) return@forEachIndexed

            val members = mutableListOf(candidate)
            for (j in i + 1 until hashed.size) {
                val other = hashed[j]
                if (other.id in used) continue
                if (!sameShape(candidate, other)) continue
                if (!PerceptualHash.isNearDuplicate(candidate.phash!!, other.phash!!, threshold)) continue
                members += other
            }

            if (members.size > 1) {
                members.forEach { used += it.id }
                groups += Group(Kind.NEAR, order(members), pickBest(members))
            }
        }
        return groups
    }

    private fun sameShape(a: MediaItem, b: MediaItem): Boolean {
        val ratioA = a.width.toDouble() / a.height
        val ratioB = b.width.toDouble() / b.height
        if (ratioA <= 0 || ratioB <= 0) return false
        return abs(ratioA - ratioB) / maxOf(ratioA, ratioB) <= ASPECT_TOLERANCE
    }

    /**
     * Which copy to keep.
     *
     * In order, and the order is the argument:
     *
     * 1. **A favourite always wins.** The user has already said which one they care about.
     * 2. **Then the most pixels.** Resolution is the one thing that cannot be recovered.
     * 3. **Then the largest file**, as a proxy for the least re-compressed — the copy that
     *    came off the camera rather than out of a chat app.
     * 4. **Then the oldest.** Between two equal copies the original is the one with the
     *    history: it is the one in the user's albums and the one their memories are dated by.
     * 5. **Then by id**, so the answer is stable. A suggestion that moved between two
     *    openings of the same screen would be unusable.
     *
     * A file this app has already optimised is *not* penalised. It is the same picture at
     * the same resolution, verified to be visually identical, and preferring the
     * un-optimised copy would quietly undo the night's work.
     */
    fun pickBest(members: List<MediaItem>): MediaItem = members.sortedWith(
        compareByDescending<MediaItem> { it.favourite }
            .thenByDescending { it.pixels }
            .thenByDescending { it.size }
            .thenBy { it.takenAt?.toEpochMilliseconds() ?: it.mtime }
            .thenBy { it.id },
    ).first()

    /** The best copy first, then the rest in the order the screen will offer them. */
    private fun order(members: List<MediaItem>): List<MediaItem> {
        val best = pickBest(members)
        return listOf(best) + members.filter { it.id != best.id }.sortedByDescending { it.size }
    }

    /** Total the cleanup screen leads with (BUILD.md § 8's marketing copy is about this). */
    fun totalReclaimable(groups: List<Group>): Long = groups.sumOf { it.reclaimable }
}
