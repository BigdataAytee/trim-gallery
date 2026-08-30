package app.trimgallery.core.domain.compress

import app.trimgallery.core.domain.billing.Entitlements
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.SkipReason

/**
 * "Compress now" — the one path allowed to encode on battery (BUILD.md rule 1).
 *
 * > 1. Never encode on battery unless the user explicitly taps "Compress now."
 *
 * > Viewer or long-press → Compress now → sheet with estimated size and time → Start.
 * > Options: "Watch while it works" (play-to-compress) or progress bar. End: "Now 165 MB
 * > (was 380 MB)" with Share / Replace original / Keep both. On battery: this is allowed;
 * > note "Uses battery" shown once. — USER_JOURNEY.md § 6
 *
 * Everything here is a decision, not plumbing, and three of the decisions are worth
 * stating out loud because they are the ones a later change is most likely to get wrong:
 *
 * 1. **This is the only place the battery rule is relaxed, and only for one file the user
 *    named.** [decide] takes no "queue" and returns nothing that could be applied to a
 *    second item. A bulk "compress everything now" would be the night pass running on
 *    battery with a different label, which is the thing rule 1 forbids.
 * 2. **It does not replace anything by itself.** The night pass replaces because the user
 *    asked it to, once, in Settings. Compress now ends on a sheet with three buttons, and
 *    until one is pressed the original has not been touched. [Finish] is that choice.
 * 3. **A user's explicit tap can override "not worth it". It cannot override "this would
 *    lose data" or "we already tried".** That split is [blockedBy], and it is the whole
 *    reason this class exists rather than a boolean on the menu item.
 */
object CompressNow {

    /** Why Compress now is unavailable for an item. All of these are shown to the user. */
    enum class Refusal {
        /** MONETIZATION.md: five a day on Free. */
        DAILY_LIMIT_REACHED,

        /**
         * Trim already optimised this file.
         *
         * The generational-loss guard from `MediaItem.optimisedAt`: every encode targets
         * VMAF 95 *against what it is given*, so a second pass measures quality against an
         * already-lossy copy and the third is visible. The night pass avoids this by never
         * re-queueing its own output; without this rule Compress now would be a button
         * that walks around that protection, five times a day, on the files the user cares
         * about most.
         */
        ALREADY_OPTIMISED,

        /** Re-encoding would drop something: HDR, a Motion Photo's video, RAW sensor data. */
        WOULD_LOSE_DATA,

        /** The phone or the format cannot do it — a capability, not a preference. */
        NOT_SUPPORTED,

        /** The search already ran and could not hold the quality. Deterministic; it would fail again. */
        QUALITY_UNREACHABLE,

        /** Not on the device yet. */
        NOT_DOWNLOADED,

        /** A job for this item is running right now. */
        ALREADY_RUNNING,
    }

    /**
     * What the sheet says before the user commits.
     *
     * Both numbers are nullable and neither is invented. Triage fills `estSaving` from a
     * probe, and the per-camera predictor learns the encode speed after about twenty files
     * (PROJECT.md); before either exists the honest sheet says how big the file is and
     * offers to start, rather than showing a guess the result will contradict. A "saves
     * about 200 MB" that turns into 40 MB is the kind of small lie that costs a user's
     * trust in every other number the app shows them.
     */
    data class Estimate(
        val originalSize: Long,
        /** Bytes triage or the predictor expects back, or null when nothing has measured it. */
        val expectedSaving: Long?,
        /** Wall-clock milliseconds, or null when this device's encode speed is unknown. */
        val expectedMs: Long?,
    ) {
        val expectedNewSize: Long?
            get() = expectedSaving?.let { (originalSize - it).coerceAtLeast(0) }
    }

    /** The answer to "can the user tap this, and what happens if they do". */
    sealed interface Decision {
        data class Allowed(
            val estimate: Estimate,
            /** Play-to-compress is offered only where there is something to watch. */
            val mayWatchWhileWorking: Boolean,
            /** USER_JOURNEY.md § 6: *"note 'Uses battery' shown once"*. */
            val showBatteryNote: Boolean,
            /**
             * Triage did not think this was worth doing.
             *
             * Not a refusal — the user asked, and it is their battery. But the sheet says
             * so, because the likely outcome is "no smaller version was possible" and a
             * user who was warned reads that as the app being careful rather than broken.
             */
            val unlikelyToHelp: Boolean = false,
        ) : Decision

        data class Refused(val refusal: Refusal, val explanation: String) : Decision {
            /** MONETIZATION.md § Conversion moments: the only refusal Pro removes. */
            val offerPro: Boolean get() = refusal == Refusal.DAILY_LIMIT_REACHED
        }
    }

    /**
     * What the user picks when it is done (USER_JOURNEY.md § 6).
     *
     * `KEEP_BOTH` and `REPLACE_ORIGINAL` both write into the user's folder, so both go
     * through the platform `Replacer` and nothing else (ARCHITECTURE.md § 14, enforced by
     * a build guard). `SHARE` hands out the app-private temp file and touches the library
     * not at all — which is why a user who only wants to send a smaller copy to someone
     * never risks their original.
     */
    enum class Finish {
        SHARE,
        REPLACE_ORIGINAL,
        KEEP_BOTH,
        ;

        val writesToLibrary: Boolean get() = this != SHARE
    }

    /**
     * Whether an explicit tap may proceed, and what the sheet should say.
     *
     * @param usedToday how many Compress now runs have *started* today. Started, not
     *   finished: counting completions would let a user who cancels at 99% do it forever,
     *   and the battery has already been spent either way.
     */
    fun decide(
        item: MediaItem,
        tier: Tier,
        usedToday: Int,
        expectedSaving: Long? = item.estSaving,
        realtimeMultiple: Double? = null,
        batteryNoteSeen: Boolean = false,
    ): Decision {
        blockedBy(item)?.let { return Decision.Refused(it, explain(it)) }

        if (!Entitlements.mayCompressNow(tier, usedToday)) {
            return Decision.Refused(Refusal.DAILY_LIMIT_REACHED, explain(Refusal.DAILY_LIMIT_REACHED))
        }

        return Decision.Allowed(
            estimate = Estimate(
                originalSize = item.size,
                expectedSaving = expectedSaving?.takeIf { it > 0 },
                expectedMs = expectedMs(item, realtimeMultiple),
            ),
            mayWatchWhileWorking = item.kind == MediaKind.VIDEO,
            showBatteryNote = !batteryNoteSeen,
            unlikelyToHelp = item.skipReason in NOT_WORTH_IT,
        )
    }

    /**
     * The refusals, in the order they are checked.
     *
     * Item facts before entitlement, deliberately: a user whose file cannot be optimised at
     * all should be told *that*, not shown a Pro offer for a button that would do nothing
     * even after they paid. MONETIZATION.md § Conversion moments — no dark patterns.
     */
    private fun blockedBy(item: MediaItem): Refusal? = when {
        item.status == MediaStatus.PROCESSING -> Refusal.ALREADY_RUNNING
        item.optimisedAt != null -> Refusal.ALREADY_OPTIMISED
        item.kind == MediaKind.FILE -> Refusal.NOT_SUPPORTED
        item.skipReason in LOSES_DATA -> Refusal.WOULD_LOSE_DATA
        item.skipReason == SkipReason.IN_CLOUD_ONLY -> Refusal.NOT_DOWNLOADED
        item.skipReason in UNSUPPORTED -> Refusal.NOT_SUPPORTED
        item.skipReason == SkipReason.COULD_NOT_REACH_QUALITY -> Refusal.QUALITY_UNREACHABLE
        else -> null
    }

    /** DESIGN_SYSTEM.md § Copy tone: short, calm, concrete; no apology and no blame. */
    fun explain(refusal: Refusal): String = when (refusal) {
        Refusal.DAILY_LIMIT_REACHED ->
            "You've used today's ${Entitlements.FREE_COMPRESS_NOW_PER_DAY} Compress now runs. " +
                "They come back tomorrow, or Pro removes the limit."
        Refusal.ALREADY_OPTIMISED ->
            "Trim already optimised this one. Doing it again would lose quality without freeing much."
        Refusal.WOULD_LOSE_DATA ->
            "Optimising this would drop data the file carries, so Trim leaves it alone."
        Refusal.NOT_SUPPORTED ->
            "This phone can't optimise this file."
        Refusal.QUALITY_UNREACHABLE ->
            "Trim already tried this one and couldn't make it smaller without a visible difference."
        Refusal.NOT_DOWNLOADED ->
            "This one isn't on the phone yet. Download it and try again."
        Refusal.ALREADY_RUNNING ->
            "Trim is working on this one now."
    }

    /**
     * "Now 165 MB (was 380 MB)" — USER_JOURNEY.md § 6, and the numbers are the measured
     * ones, after the encode, not the estimate the sheet opened with.
     */
    fun describeResult(originalSize: Long, newSize: Long): String =
        "Now ${megabytes(newSize)} (was ${megabytes(originalSize)})"

    /**
     * Encode time from the clip's own duration and a measured speed.
     *
     * Null in, null out. There is no default multiple here because there is no honest one:
     * the same phone encodes 4K HEVC and 1080p H.264 at speeds that differ by more than the
     * estimate would be worth, and PROJECT.md's per-camera table exists precisely because
     * one number for every file is wrong for most of them.
     */
    private fun expectedMs(item: MediaItem, realtimeMultiple: Double?): Long? {
        if (realtimeMultiple == null || realtimeMultiple <= 0.0) return null
        val duration = item.duration ?: return null
        return (duration / realtimeMultiple).toLong().coerceAtLeast(1)
    }

    private fun megabytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1024) {
            val gb = mb / 1024
            "${((gb * 10).toLong()) / 10.0} GB"
        } else {
            "${mb.toLong()} MB"
        }
    }

    /** Re-encoding these destroys something the container carries (BUILD.md § 2.5). */
    private val LOSES_DATA = setOf(
        SkipReason.HDR,
        SkipReason.MOTION_PHOTO,
        SkipReason.ULTRA_HDR,
        SkipReason.LIVE_PHOTO,
        SkipReason.RAW,
    )

    private val UNSUPPORTED = setOf(
        SkipReason.UNSUPPORTED_CODEC,
        SkipReason.NO_HARDWARE_ENCODER,
    )

    /**
     * Judgements, not facts.
     *
     * Triage skipped these because the saving did not justify a night's battery. A user
     * standing in front of the file, tapping a button, has different information — they may
     * want the 3 MB. The verify gate still refuses to replace a file with a larger one, so
     * the worst case is a wasted minute and an honest "no smaller version was possible".
     */
    private val NOT_WORTH_IT = setOf(
        SkipReason.ALREADY_EFFICIENT,
        SkipReason.TOO_SMALL,
        SkipReason.WOULD_NOT_SHRINK,
    )
}
