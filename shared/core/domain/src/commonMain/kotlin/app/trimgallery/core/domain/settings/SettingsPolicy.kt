package app.trimgallery.core.domain.settings

import app.trimgallery.core.domain.billing.Entitlements
import app.trimgallery.core.domain.billing.ProFeature
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.core.model.Settings

/**
 * What a settings value is allowed to be (ARCHITECTURE.md § 12, BUILD.md § 9).
 *
 * The store is a `DataStore`, which will happily persist anything. This is the thing that
 * decides what "anything" may include, and it sits between the screen and the store so that
 * both the UI and a future import of a settings backup go through the same rules.
 *
 * Two properties it holds that are easy to lose:
 *
 * - **A value that cannot be honoured is never stored.** `AndroidGuards` used to parse the
 *   stop-by time with a `runCatching { … }.getOrNull()`, which meant a typo silently became
 *   "no stop time" and the user's phone worked all night. Validating on the way in makes
 *   the read side's fallback unreachable rather than load-bearing.
 * - **Losing Pro clamps settings, it does not break them.** [sanitise] runs on every read,
 *   so a lapsed user gets Standard quality and 30-day retention rather than an app that
 *   silently keeps using entitlements they no longer have — or, worse, one that refuses to
 *   start because a stored value is now illegal.
 */
object SettingsPolicy {

    /**
     * The nightly cap, in minutes (BUILD.md § 6 defaults to 60).
     *
     * The floor is five minutes because a shorter cap cannot finish a single 4K clip, and a
     * cap that always stops mid-file does nothing but waste the battery it was set to save.
     * The ceiling is eight hours: past that the cap is not the binding constraint — the
     * alarm, the charge and the heat all are — and a bigger number would only look like a
     * promise the night cannot keep.
     */
    const val MIN_CAP_MINUTES = 5
    const val MAX_CAP_MINUTES = 480

    /** 24-hour wall clock, the format `LocalTime.parse` accepts. */
    private val TIME = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /** A Pro-only switch, as the Settings screen shows it. */
    enum class Locked(val feature: ProFeature) {
        COMPACT_MODE(ProFeature.COMPACT_MODE),
        AV1(ProFeature.AV1_ENCODE),
        CAREFUL_VERIFY(ProFeature.CAREFUL_VERIFY),
        REVERSIBLE_JXL(ProFeature.REVERSIBLE_JXL),
        OFFLOAD(ProFeature.OFFLOAD_TO_EXTERNAL),
        EXTENDED_UNDO(ProFeature.EXTENDED_UNDO_RETENTION),
    }

    /**
     * Which switches are locked for this tier.
     *
     * MONETIZATION.md § Conversion moments: *"locked with a one-line explanation"* — shown,
     * not hidden. A user who cannot see what Pro adds has no reason to buy it, and a user
     * who finds a hidden feature later feels tricked.
     */
    fun lockedFor(tier: Tier): Set<Locked> = Locked.entries.filterNot { Entitlements.allows(tier, it.feature) }.toSet()

    fun lockExplanation(locked: Locked): String = when (locked) {
        Locked.COMPACT_MODE -> "Compact mode frees more space at a slightly lower quality target. Pro."
        Locked.AV1 -> AV1_EXPLANATION
        Locked.CAREFUL_VERIFY -> "Careful verify checks more of each video before replacing it. Pro."
        Locked.REVERSIBLE_JXL -> "Reversible mode stores photos as JPEG XL, which can be turned back. Pro."
        Locked.OFFLOAD -> "Offload moves originals to an SD card or USB drive instead of the bin. Pro."
        Locked.EXTENDED_UNDO -> "Pro keeps originals for up to ${Entitlements.PRO_MAX_RETENTION_DAYS} days."
    }

    /**
     * What the AV1 toggle costs as well as what it saves (milestone 12).
     *
     * AV1 is worth roughly a third off HEVC at the same quality, and the honest sentence
     * has to carry the other half too: the file stays perfectly playable on the phone that
     * made it, and an older phone, a television or a car it is shared to may not open it.
     * Offering the saving without the caveat would be selling a setting whose cost only
     * shows up when someone else cannot watch the video.
     */
    const val AV1_EXPLANATION =
        "AV1 makes files about a third smaller again, on phones that can encode it. " +
            "Older phones, TVs and cars may not be able to play them. Pro."

    /**
     * BUILD.md § 9: *"Compact 90 with warning"*.
     *
     * The warning is not a scare. It is the one place the app says out loud what "visually
     * lossless" costs, and PROJECT.md § Quality and reversibility requires it be said
     * plainly rather than buried in a percentage.
     */
    const val COMPACT_WARNING =
        "Compact frees noticeably more space. On close inspection some videos will look " +
            "slightly softer than the original. Your originals are still kept."

    /**
     * The settings as they may actually be stored.
     *
     * Everything out of range is clamped rather than rejected: a settings screen that
     * refuses to save is a settings screen the user fights, and every value here has a
     * sensible nearest legal neighbour. The one exception is the stop-by time, where the
     * nearest legal neighbour to "25:00" is *nothing* — so it becomes null, which means "no
     * stop time" and is a state the user can see and correct.
     */
    fun sanitise(requested: Settings, tier: Tier): Settings = requested.copy(
        qualityTarget = if (Entitlements.allows(tier, ProFeature.COMPACT_MODE)) {
            requested.qualityTarget
        } else {
            QualityTarget.STANDARD
        },
        photoReversible = requested.photoReversible && Entitlements.allows(tier, ProFeature.REVERSIBLE_JXL),
        allowAv1 = requested.allowAv1 && Entitlements.allows(tier, ProFeature.AV1_ENCODE),
        carefulVerify = requested.carefulVerify && Entitlements.allows(tier, ProFeature.CAREFUL_VERIFY),
        nightlyCapMinutes = requested.nightlyCapMinutes.coerceIn(MIN_CAP_MINUTES, MAX_CAP_MINUTES),
        undoRetentionDays = Entitlements.retentionDays(tier, requested.undoRetentionDays),
        stopByTime = requested.stopByTime?.takeIf { TIME.matches(it) },
    )

    /** Whether a stop-by time can be stored at all. Exposed so the field can say so as it is typed. */
    fun isValidStopBy(text: String?): Boolean = text == null || TIME.matches(text)

    /**
     * Whether a change makes the library's triage verdicts stale.
     *
     * Triage records *why* a file was skipped, and two of those reasons depend on settings
     * rather than on the file: `WOULD_NOT_SHRINK` is measured against the quality target,
     * and `UNSUPPORTED_CODEC` can change when AV1 becomes available. A user who turns on
     * Compact to free more space and sees the same four hundred skipped files has been told
     * the setting did nothing.
     *
     * Deliberately *not* including the photo format: changing JPEG to HEIC changes the
     * output, not whether there is one, and re-triaging a hundred thousand photos to
     * discover that is a night spent on nothing.
     */
    fun invalidatesTriage(old: Settings, new: Settings): Boolean =
        old.qualityTarget != new.qualityTarget || old.allowAv1 != new.allowAv1

    /**
     * Whether a change should reschedule the night pass with the OS.
     *
     * WorkManager holds the constraints it was given; changing "start when full" without
     * telling it leaves the phone waiting for a condition the user turned off.
     */
    fun requiresReschedule(old: Settings, new: Settings): Boolean = old.startWhenFull != new.startWhenFull ||
        old.keepWorkingWhileUsing != new.keepWorkingWhileUsing ||
        old.stopByTime != new.stopByTime

    /**
     * What to tell the user, when a change needs saying out loud.
     *
     * Only three do. A settings screen that explains every toggle teaches the user to
     * dismiss explanations, and then the one about quality goes unread too.
     */
    fun notices(old: Settings, new: Settings): List<String> = buildList {
        if (old.qualityTarget != QualityTarget.COMPACT && new.qualityTarget == QualityTarget.COMPACT) {
            add(COMPACT_WARNING)
        }
        if (old.faceClusteringEnabled && !new.faceClusteringEnabled) {
            add(
                "Trim will stop grouping people, and the face data it already made is deleted. " +
                    "None of it ever left this phone.",
            )
        }
        if (new.undoRetentionDays < old.undoRetentionDays) {
            add(
                "New originals will be kept for ${new.undoRetentionDays} days. " +
                    "Originals already in the bin keep the date they were given.",
            )
        }
    }
}
