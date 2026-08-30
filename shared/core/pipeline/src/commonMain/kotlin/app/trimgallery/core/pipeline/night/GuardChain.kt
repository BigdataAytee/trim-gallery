package app.trimgallery.core.pipeline.night

import app.trimgallery.core.domain.billing.Entitlements
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.StopReason
import app.trimgallery.engine.GuardResult
import app.trimgallery.engine.PauseReason
import kotlin.time.Instant

/**
 * Everything the guards need to know, read once per decision.
 *
 * One struct rather than seven getters so that a decision is made against a single
 * consistent snapshot: polling the battery between the charging check and the
 * battery-full check is how you get "stopped because unplugged" on a phone that is
 * plugged in.
 */
data class NightConditions(
    val now: Instant,
    val appInForeground: Boolean,
    val charging: Boolean,
    val batteryFull: Boolean,
    /** `PowerManager.getThermalHeadroom(30)`. Higher is *hotter*; NaN means unknown. */
    val thermalHeadroom: Float,
    /** The earlier of "30 min before the alarm" and the user's stop-by time. */
    val deadline: Instant?,
    val freeBytes: Long,
    /** The largest file still queued, which sets how much room the pass needs. */
    val largestPendingBytes: Long,
    /** Work done tonight, excluding time stood down. */
    val workedMs: Long,
    val bytesFreedThisMonth: Long,
    /** What the next file is expected to save, for the free-tier cap. */
    val nextSavingBytes: Long = 0,
)

/**
 * The stop conditions, in the order ARCHITECTURE.md § 9 fixes:
 *
 * > Foreground → Charging → BatteryFull? → Thermal → StopBy/Alarm → Storage → Cap
 *
 * The order is not an implementation detail. Several conditions are usually true at once
 * on a phone that has been working all night, and the order decides which one the user is
 * *told* about — in History, and in the "Paused for heat" line. Foreground comes first
 * because the user is holding the phone right now and "paused because you're using it" is
 * the true answer; heat comes before the alarm because a hot phone is a problem now and a
 * deadline is a problem later.
 *
 * Pause means *wait and re-check*; stop means *this night is over*. The split follows what
 * can actually change while plugged in: heat passes, a foregrounded app is closed, a
 * battery fills. An unplugged phone, a passed deadline and a spent cap do not undo
 * themselves.
 */
class GuardChain(
    val thermal: ThermalGate = ThermalGate(),
    private val config: Config = Config(),
) {

    data class Config(
        /**
         * How many consecutive storage pauses before the night is called off.
         *
         * Storage is a pause rather than a stop (ARCHITECTURE.md § 13) because the pass
         * itself frees space — a completed replace, an offload, the undo sweep — so the
         * condition genuinely can clear. But a phone that is simply full will never clear
         * it, and polling until morning would spend the battery this app exists to
         * protect. Six checks at the thermal poll interval is half a minute of patience.
         */
        val storagePauseLimit: Int = 6,

        /** BUILD.md § 6: *"storage below 2× largest pending file"*. */
        val storageHeadroomFactor: Int = 2,
    )

    private var consecutiveStoragePauses = 0

    /**
     * The verdict for one snapshot of the world.
     *
     * The § 9 order decides which reason the user is told **among conditions of equal
     * severity**. A stop always wins over a pause, whatever the order says: severity is
     * about behaviour, not wording, and letting an earlier pause mask a later stop would
     * have the pass sit "paused because you're using the phone" while running on battery —
     * the exact thing BUILD.md rule 6 exists to prevent. So pauses are collected and the
     * chain is evaluated to the end; a stop returns immediately, because § 9's order holds
     * among stops too.
     */
    fun evaluate(conditions: NightConditions, settings: Settings, tier: Tier): GuardResult {
        var firstPause: PauseReason? = null
        fun pause(reason: PauseReason) { if (firstPause == null) firstPause = reason }

        // 1. Foreground. BUILD.md rule 7: the gallery must stay at refresh rate, so
        //    background work yields to the person using the phone. The one exception is
        //    the explicit "keep working while I use the phone" setting, which BUILD.md § 9
        //    qualifies as charging-only.
        if (conditions.appInForeground && !(settings.keepWorkingWhileUsing && conditions.charging)) {
            pause(PauseReason.FOREGROUND)
        }

        // 2. Charging. Unplugging stops the pass within seconds (USER_JOURNEY.md § 3):
        //    this app must never be the reason a phone is flat in the morning.
        if (!conditions.charging) return stop(PauseReason.NOT_CHARGING)

        // 3. Battery full, if the user asked to wait for it. A pause, not a stop — the
        //    phone is plugged in and will get there.
        if (settings.startWhenFull && !conditions.batteryFull) pause(PauseReason.BATTERY_NOT_FULL)

        // 4. Thermal, with hysteresis so it cannot flap. Fed every reading, even when a
        //    pause is already pending, so the gate's state and its count stay true to what
        //    the sensor actually did.
        if (thermal.update(conditions.thermalHeadroom, conditions.now.toEpochMilliseconds())) {
            pause(PauseReason.THERMAL)
        }

        // 5. The alarm and the stop-by time.
        if (AlarmWindow.reached(conditions.now, conditions.deadline)) return stop(PauseReason.STOP_BY_TIME)

        // 6. Storage. Room for the largest pending file *and* its replacement, since both
        //    exist at once between the encode and the commit.
        if (conditions.freeBytes < conditions.largestPendingBytes * config.storageHeadroomFactor) {
            consecutiveStoragePauses += 1
            if (consecutiveStoragePauses >= config.storagePauseLimit) return stop(PauseReason.STORAGE_LOW)
            pause(PauseReason.STORAGE_LOW)
        } else {
            consecutiveStoragePauses = 0
        }

        // 7. The caps. Tonight's minutes first, then the month's gigabytes: a user who is
        //    out of both should be told the recoverable one, because tomorrow fixes it and
        //    no offer needs to be made.
        if (conditions.workedMs >= settings.nightlyCapMinutes * MS_PER_MINUTE) {
            return stop(PauseReason.CAP_REACHED)
        }
        if (!Entitlements.mayOptimise(tier, conditions.bytesFreedThisMonth, conditions.nextSavingBytes)) {
            return stop(PauseReason.FREE_TIER_CAP)
        }

        return firstPause?.let { paused(it) } ?: GuardResult.Proceed
    }

    fun reset() {
        thermal.reset()
        consecutiveStoragePauses = 0
    }

    private fun paused(reason: PauseReason) = GuardResult.Pause(reason)
    private fun stop(reason: PauseReason) = GuardResult.Stop(reason)

    companion object {
        private const val MS_PER_MINUTE = 60_000L

        /**
         * What a run that ended for [reason] records in `run_session.stop_reason`.
         *
         * A run can end while merely *paused* — the OS takes the window back, or the phone
         * is unplugged — and what the user is owed then is why it was standing down, not
         * "cancelled". SCHEMA.md's stop reasons include FOREGROUND, THERMAL and STORAGE
         * for exactly that case.
         */
        fun stopReasonFor(reason: PauseReason): StopReason = when (reason) {
            PauseReason.FOREGROUND -> StopReason.FOREGROUND
            PauseReason.NOT_CHARGING -> StopReason.UNPLUGGED
            PauseReason.BATTERY_NOT_FULL -> StopReason.UNPLUGGED
            PauseReason.THERMAL -> StopReason.THERMAL
            PauseReason.STOP_BY_TIME -> StopReason.STOP_BY
            PauseReason.STORAGE_LOW -> StopReason.STORAGE
            PauseReason.CAP_REACHED -> StopReason.CAP
            PauseReason.FREE_TIER_CAP -> StopReason.CAP_FREE_TIER
        }
    }
}
