package app.trimgallery.core.domain.billing

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * What the free tier can do, and what Pro unlocks (MONETIZATION.md § Phase 1).
 *
 * Pure decision logic, deliberately: a paywall that is wrong is either a broken promise or
 * a lost sale, and both are cheap to test here and expensive to notice in production.
 *
 * The three rules that constrain everything below, from MONETIZATION.md § Conversion
 * moments, are enforced structurally rather than by convention:
 *
 * 1. **Restore is never gated.** There is no code path here that can withhold it.
 * 2. **Indexing continues when the cap is hit** — only optimisation pauses, so the gallery
 *    keeps improving even for a user who has stopped paying attention to the offer.
 * 3. **No feature is removed from Free after launch**, which is why the free set is
 *    written as an explicit list rather than as "everything except".
 */
enum class Tier { FREE, PRO }

/** Everything behind the paywall, one entry per row of the MONETIZATION.md table. */
enum class ProFeature {
    UNLIMITED_OPTIMISATION,
    UNLIMITED_COMPRESS_NOW,
    DUPLICATE_BULK_ACTIONS,
    AV1_ENCODE,
    OFFLOAD_TO_EXTERNAL,
    CAREFUL_VERIFY,
    COMPACT_MODE,
    REVERSIBLE_JXL,
    EXTENDED_UNDO_RETENTION,
}

object Entitlements {

    /** MONETIZATION.md: *"up to 3 GB freed per month"*. */
    const val FREE_MONTHLY_BYTES = 3L * 1024 * 1024 * 1024

    /** MONETIZATION.md: *"Compress now — 5 per day"*. */
    const val FREE_COMPRESS_NOW_PER_DAY = 5

    /** Undo retention: 7 days free, up to 90 on Pro. */
    const val FREE_RETENTION_DAYS = 7
    const val PRO_MAX_RETENTION_DAYS = 90

    /** MONETIZATION.md § Conversion moments: *"never nag more than once per week"*. */
    val OFFER_COOLDOWN = 7.days

    fun allows(tier: Tier, @Suppress("UNUSED_PARAMETER") feature: ProFeature): Boolean = tier == Tier.PRO

    /**
     * Bytes this tier may still free this month.
     *
     * `Long.MAX_VALUE` for Pro rather than a nullable: every caller then does the same
     * arithmetic, and a null would need a branch at each of them — which is where a "0
     * remaining" bug for paying users would eventually come from.
     */
    fun remainingBytes(tier: Tier, freedThisMonth: Long): Long = when (tier) {
        Tier.PRO -> Long.MAX_VALUE
        Tier.FREE -> (FREE_MONTHLY_BYTES - freedThisMonth).coerceAtLeast(0)
    }

    fun capReached(tier: Tier, freedThisMonth: Long): Boolean = remainingBytes(tier, freedThisMonth) == 0L

    /**
     * Whether one more file may be optimised.
     *
     * Checked against the file's *estimated* saving before the encode, not after: an
     * encode that finishes and is then refused has already spent the battery, and the user
     * would see a file skipped for no reason they were told about.
     *
     * A file larger than the whole remaining allowance is still allowed through when
     * nothing has been freed yet — otherwise a user whose first video would save 4 GB
     * never gets to use the free tier at all, which is a worse first run than going 1 GB
     * over the cap once.
     */
    fun mayOptimise(tier: Tier, freedThisMonth: Long, estimatedSaving: Long): Boolean {
        if (tier == Tier.PRO) return true
        if (freedThisMonth <= 0) return true
        return estimatedSaving <= remainingBytes(tier, freedThisMonth)
    }

    /** Indexing, search, people and duplicates keep running after the cap (BUILD.md § 7). */
    fun mayIndex(@Suppress("UNUSED_PARAMETER") tier: Tier): Boolean = true

    /**
     * Restore is never gated. Not by tier, not by cap, not by anything.
     *
     * MONETIZATION.md says so twice — under Conversion moments and again under Refunds and
     * trust — because it is the promise the whole product rests on: the original is the
     * undo (PROJECT.md § Quality and reversibility), and a paywall in front of it would
     * make the app's core claim untrue.
     */
    fun mayRestore(): Boolean = true

    fun mayCompressNow(tier: Tier, usedToday: Int): Boolean = tier == Tier.PRO || usedToday < FREE_COMPRESS_NOW_PER_DAY

    /**
     * The retention the user may actually choose, clamped to their tier.
     *
     * Clamped rather than rejected: a user who had 30 days on Pro and lapses should get 7,
     * not an error. Their *existing* undo entries keep the expiry they were created with —
     * that is a property of the row, and shortening it retroactively would delete
     * originals the user was promised.
     */
    fun retentionDays(tier: Tier, requested: Int): Int = when (tier) {
        Tier.FREE -> requested.coerceIn(1, FREE_RETENTION_DAYS)
        Tier.PRO -> requested.coerceIn(1, PRO_MAX_RETENTION_DAYS)
    }

    /**
     * Whether to show the Pro card at all.
     *
     * MONETIZATION.md § Conversion moments: never interrupt the viewer, never more than
     * once a week. Both are decided here so no screen has to remember to.
     */
    fun mayShowOffer(tier: Tier, lastShown: Instant?, now: Instant, inViewer: Boolean): Boolean = when {
        tier == Tier.PRO -> false
        inViewer -> false
        lastShown == null -> true
        else -> now - lastShown >= OFFER_COOLDOWN
    }
}
