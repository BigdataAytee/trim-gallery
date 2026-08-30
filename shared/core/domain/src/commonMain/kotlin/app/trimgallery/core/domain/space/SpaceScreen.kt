package app.trimgallery.core.domain.space

import app.trimgallery.core.domain.billing.Entitlements
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.RunSession
import app.trimgallery.core.model.StopReason
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Everything the Space screen shows (BUILD.md § 9, USER_JOURNEY.md § 12).
 *
 * > Space screen: running total, animated progress ring during a run, history with restore,
 * > energy estimate.
 *
 * This is the screen that has to answer "is this app worth having on my phone?", so every
 * number on it is one the user could in principle check, and none of them is a projection
 * dressed up as a fact. The estimate of what is still possible is labelled as an estimate;
 * the space already freed is a sum of what actually happened.
 */
object SpaceScreen {

    /**
     * Where the progress ring is.
     *
     * A run that is *paused* is deliberately distinguishable from one that is working:
     * USER_JOURNEY.md § 3 shows progress rings only if the user happens to open the app
     * mid-night, and a ring that spins while the pass is stood down for heat is a lie the
     * user can catch by feeling the phone.
     */
    sealed interface Progress {
        data object Idle : Progress
        data class Working(val done: Int, val total: Int, val bytesFreed: Long) : Progress {
            /** 0..1, or null when the total is not yet known. */
            val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        }
        data class Paused(val reason: StopReason) : Progress
    }

    data class State(
        /** Everything ever freed, across every night. */
        val totalFreed: Long,
        val progress: Progress,
        /** Bytes freed this calendar month, against the free tier's allowance. */
        val freedThisMonth: Long,
        val monthlyCap: Long?,
        /** Days until the cap resets, for "resets in 12 days". */
        val capResetsInDays: Int?,
        /** What triage thinks is still available. An estimate, and labelled as one. */
        val projectedSaving: Long,
        val energyWh: Double,
        /** MONETIZATION.md § Conversion moments: the card, when it is due. */
        val showProOffer: Boolean,
    ) {
        val capReached: Boolean get() = monthlyCap != null && freedThisMonth >= monthlyCap

        /** 0..1 for the cap ring, or null on Pro where there is no cap to draw. */
        val capFraction: Float?
            get() = monthlyCap?.let { (freedThisMonth.toFloat() / it).coerceIn(0f, 1f) }
    }

    /**
     * @param sessions every recorded night. The totals are summed from these rather than
     *   kept as a counter, for the same reason the monthly cap is: a counter drifts from
     *   what happened the first time a run is killed mid-write, and a number the user
     *   cannot trust is worse than no number.
     * @param projectedSaving the sum of `est_saving` over current candidates.
     */
    @Suppress("LongParameterList")
    fun state(
        sessions: List<RunSession>,
        current: RunSession?,
        queueSize: Int,
        projectedSaving: Long,
        tier: Tier,
        now: Instant,
        monthStart: Instant,
        nextMonthStart: Instant,
        lastOfferShown: Instant? = null,
        inViewer: Boolean = false,
    ): State {
        val freedThisMonth = sessions
            .filter { it.startedAt >= monthStart.toEpochMilliseconds() }
            .sumOf { it.bytesFreed }

        val cap = when (tier) {
            Tier.FREE -> Entitlements.FREE_MONTHLY_BYTES
            Tier.PRO -> null
        }

        return State(
            totalFreed = sessions.sumOf { it.bytesFreed },
            progress = progressOf(current, queueSize),
            freedThisMonth = freedThisMonth,
            monthlyCap = cap,
            capResetsInDays = cap?.let { daysUntil(now, nextMonthStart) },
            projectedSaving = projectedSaving,
            energyWh = sessions.sumOf { it.energyWh },
            showProOffer = Entitlements.mayShowOffer(tier, lastOfferShown, now, inViewer) &&
                cap != null && freedThisMonth >= cap,
        )
    }

    private fun progressOf(current: RunSession?, queueSize: Int): Progress {
        if (current == null || current.finishedAt != null) return Progress.Idle

        // A run with a stop reason recorded but no finish time is standing down, not
        // working: the checkpoint written while paused carries exactly that shape.
        current.stopReason?.let { return Progress.Paused(it) }

        val done = current.filesDone + current.filesSkipped + current.filesFailed
        return Progress.Working(done = done, total = done + queueSize, bytesFreed = current.bytesFreed)
    }

    /**
     * Whole days, rounded up.
     *
     * Up, so a cap resetting in eleven hours says "1 day" rather than "0 days": a zero next
     * to something the user is waiting for reads as "never".
     */
    private fun daysUntil(now: Instant, then: Instant): Int {
        if (then <= now) return 0
        val remaining: Duration = then - now
        val whole = remaining.inWholeDays.toInt()
        return if (remaining.inWholeHours % 24 > 0) whole + 1 else whole
    }

    /**
     * The line under the total.
     *
     * DESIGN_SYSTEM.md § Copy tone: numbers over adjectives, never "compress" or "shrink".
     * The projection is always hedged, because it is a projection.
     */
    fun projectionLine(state: State, format: (Long) -> String): String? {
        if (state.projectedSaving <= 0) return null
        return "About ${format(state.projectedSaving)} more possible"
    }
}
