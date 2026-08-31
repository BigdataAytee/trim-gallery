package app.trimgallery.core.pipeline.night

import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.StopReason
import app.trimgallery.engine.GuardResult
import app.trimgallery.engine.PauseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * ARCHITECTURE.md § 9: *"Foreground → Charging → BatteryFull? → Thermal → StopBy/Alarm →
 * Storage → Cap"*.
 *
 * The order is the interesting part. On a phone that has been working all night several
 * conditions are true at once, and the order decides which one the user is told about.
 */
class GuardChainTest {

    private val now = Instant.parse("2026-08-30T02:00:00Z")
    private val gb = 1024L * 1024 * 1024

    private fun conditions(
        foreground: Boolean = false,
        charging: Boolean = true,
        batteryFull: Boolean = true,
        thermal: Float = 0.2f,
        deadline: Instant? = null,
        freeBytes: Long = 50 * gb,
        largestPending: Long = gb,
        workedMs: Long = 0,
        freedThisMonth: Long = 0,
        nextSaving: Long = 0,
    ) = NightConditions(
        now = now,
        appInForeground = foreground,
        charging = charging,
        batteryFull = batteryFull,
        thermalHeadroom = thermal,
        deadline = deadline,
        freeBytes = freeBytes,
        largestPendingBytes = largestPending,
        workedMs = workedMs,
        bytesFreedThisMonth = freedThisMonth,
        nextSavingBytes = nextSaving,
    )

    private val settings = Settings()

    private fun verdict(
        chain: GuardChain = GuardChain(),
        conditions: NightConditions = conditions(),
        settings: Settings = this.settings,
        tier: Tier = Tier.PRO,
    ) = chain.evaluate(conditions, settings, tier)

    @Test
    fun `a charged, cool, idle phone proceeds`() {
        assertEquals(GuardResult.Proceed, verdict())
    }

    // ------------------------------------------------------------------ order

    @Test
    fun `foreground beats every other pause`() {
        // The user is holding the phone. "Paused because you're using it" is the true
        // answer even if the phone is also warm and part-charged.
        val everything = conditions(foreground = true, batteryFull = false, thermal = 0.95f)
        assertEquals(PauseReason.FOREGROUND, assertIs<GuardResult.Pause>(verdict(conditions = everything)).reason)
    }

    @Test
    fun `a stop is never masked by an earlier pause`() {
        // Order decides the message among conditions of equal severity; severity decides
        // behaviour. A pass sitting "paused because you're using the phone" while running
        // on battery is exactly what BUILD.md rule 6 exists to prevent.
        val unpluggedAndInUse = conditions(foreground = true, charging = false)
        assertEquals(
            PauseReason.NOT_CHARGING,
            assertIs<GuardResult.Stop>(verdict(conditions = unpluggedAndInUse)).reason,
        )

        // Same for a deadline reached while the phone happens to be hot: the run is ending
        // either way, and it is ending because of the deadline.
        val hotAtTheDeadline = conditions(thermal = 0.95f, deadline = now)
        assertEquals(
            PauseReason.STOP_BY_TIME,
            assertIs<GuardResult.Stop>(verdict(conditions = hotAtTheDeadline)).reason,
        )
    }

    @Test
    fun `unplugged beats heat, the deadline, storage and the cap`() {
        val conditions = conditions(
            charging = false,
            thermal = 0.95f,
            deadline = now,
            freeBytes = 0,
            workedMs = 99 * 60_000L,
        )
        assertEquals(PauseReason.NOT_CHARGING, assertIs<GuardResult.Stop>(verdict(conditions = conditions)).reason)
    }

    @Test
    fun `heat is reported before low storage`() {
        // Both are pauses, so the section 9 order decides. A hot phone is the thing the
        // user would want explained; a full one will be explained by the storage screen.
        val conditions = conditions(thermal = 0.95f, freeBytes = 0)
        assertEquals(PauseReason.THERMAL, assertIs<GuardResult.Pause>(verdict(conditions = conditions)).reason)
    }

    @Test
    fun `a part-charged battery is reported before heat`() {
        val conditions = conditions(batteryFull = false, thermal = 0.95f)
        assertEquals(
            PauseReason.BATTERY_NOT_FULL,
            assertIs<GuardResult.Pause>(verdict(conditions = conditions)).reason,
        )
    }

    @Test
    fun `the thermal gate still sees every reading while another pause is pending`() {
        // Otherwise the hysteresis would depend on which other guard happened to fire,
        // and "paused for heat 3x" would undercount.
        val chain = GuardChain()
        chain.evaluate(conditions(foreground = true, thermal = 0.95f), settings, Tier.PRO)
        assertEquals(1, chain.thermal.pauseCount)
        assertEquals(true, chain.thermal.isPaused)
    }

    @Test
    fun `the deadline is reported before storage and the cap`() {
        val conditions = conditions(deadline = now, freeBytes = 0, workedMs = 99 * 60_000L)
        assertEquals(PauseReason.STOP_BY_TIME, assertIs<GuardResult.Stop>(verdict(conditions = conditions)).reason)
    }

    @Test
    fun `tonight's cap is reported before the month's, because tomorrow fixes it`() {
        // Offering Pro to a user who is merely out of minutes would be a nag, not an offer.
        val conditions = conditions(workedMs = 61 * 60_000L, freedThisMonth = 3 * gb, nextSaving = gb)
        val stop = assertIs<GuardResult.Stop>(verdict(conditions = conditions, tier = Tier.FREE))
        assertEquals(PauseReason.CAP_REACHED, stop.reason)
    }

    // ---------------------------------------------------------- pause vs stop

    @Test
    fun `waiting for a full battery is a pause, because the phone is plugged in`() {
        val conditions = conditions(batteryFull = false)
        assertIs<GuardResult.Pause>(verdict(conditions = conditions))
    }

    @Test
    fun `turning off start-when-full lets a part-charged phone work`() {
        val conditions = conditions(batteryFull = false)
        assertEquals(
            GuardResult.Proceed,
            verdict(conditions = conditions, settings = settings.copy(startWhenFull = false)),
        )
    }

    @Test
    fun `keep-working-while-using overrides the foreground guard, but only on charge`() {
        val keepWorking = settings.copy(keepWorkingWhileUsing = true)
        assertEquals(
            GuardResult.Proceed,
            verdict(conditions = conditions(foreground = true), settings = keepWorking),
        )
        // BUILD.md § 9 qualifies the setting as charging-only, and the charging guard is
        // what enforces it.
        assertIs<GuardResult.Stop>(
            verdict(conditions = conditions(foreground = true, charging = false), settings = keepWorking),
        )
    }

    // -------------------------------------------------------------- storage

    @Test
    fun `storage needs room for the largest pending file and its replacement`() {
        // Both exist at once between the encode and the commit.
        assertEquals(GuardResult.Proceed, verdict(conditions = conditions(freeBytes = 2 * gb, largestPending = gb)))
        assertIs<GuardResult.Pause>(
            verdict(conditions = conditions(freeBytes = 2 * gb - 1, largestPending = gb)),
        )
    }

    @Test
    fun `storage pauses, then gives up rather than polling until morning`() {
        val chain = GuardChain(config = GuardChain.Config(storagePauseLimit = 3))
        val low = conditions(freeBytes = 0)
        assertIs<GuardResult.Pause>(chain.evaluate(low, settings, Tier.PRO))
        assertIs<GuardResult.Pause>(chain.evaluate(low, settings, Tier.PRO))
        val third = assertIs<GuardResult.Stop>(chain.evaluate(low, settings, Tier.PRO))
        assertEquals(PauseReason.STORAGE_LOW, third.reason)
    }

    @Test
    fun `a storage pause that clears resets the patience counter`() {
        // A completed replace or the undo sweep genuinely frees space mid-run, which is
        // why storage is a pause at all.
        val chain = GuardChain(config = GuardChain.Config(storagePauseLimit = 3))
        assertIs<GuardResult.Pause>(chain.evaluate(conditions(freeBytes = 0), settings, Tier.PRO))
        assertIs<GuardResult.Pause>(chain.evaluate(conditions(freeBytes = 0), settings, Tier.PRO))
        assertEquals(GuardResult.Proceed, chain.evaluate(conditions(), settings, Tier.PRO))
        assertIs<GuardResult.Pause>(chain.evaluate(conditions(freeBytes = 0), settings, Tier.PRO))
    }

    // ------------------------------------------------------------------ caps

    @Test
    fun `the nightly cap is on work, and defaults to sixty minutes`() {
        assertEquals(60, Settings().nightlyCapMinutes)
        assertEquals(GuardResult.Proceed, verdict(conditions = conditions(workedMs = 59 * 60_000L)))
        assertIs<GuardResult.Stop>(verdict(conditions = conditions(workedMs = 60 * 60_000L)))
    }

    @Test
    fun `the free tier stops for the month, with its own reason`() {
        val conditions = conditions(freedThisMonth = 3 * gb, nextSaving = gb)
        val stop = assertIs<GuardResult.Stop>(verdict(conditions = conditions, tier = Tier.FREE))
        assertEquals(PauseReason.FREE_TIER_CAP, stop.reason)
        assertEquals(StopReason.CAP_FREE_TIER, GuardChain.stopReasonFor(stop.reason))
    }

    @Test
    fun `Pro is never stopped by the monthly cap`() {
        val conditions = conditions(freedThisMonth = 900 * gb, nextSaving = 9 * gb)
        assertEquals(GuardResult.Proceed, verdict(conditions = conditions, tier = Tier.PRO))
    }

    // ----------------------------------------------------- reason → stop reason

    @Test
    fun `every pause reason maps to a stop reason the History screen can show`() {
        // A run can end while merely paused — the OS takes the window back — and what the
        // user is owed then is why it was standing down, not "cancelled".
        PauseReason.entries.forEach { reason ->
            GuardChain.stopReasonFor(reason) // total by construction; fails to compile otherwise
        }
        assertEquals(StopReason.UNPLUGGED, GuardChain.stopReasonFor(PauseReason.NOT_CHARGING))
        assertEquals(StopReason.THERMAL, GuardChain.stopReasonFor(PauseReason.THERMAL))
        assertEquals(StopReason.FOREGROUND, GuardChain.stopReasonFor(PauseReason.FOREGROUND))
        assertEquals(StopReason.STOP_BY, GuardChain.stopReasonFor(PauseReason.STOP_BY_TIME))
        assertEquals(StopReason.STORAGE, GuardChain.stopReasonFor(PauseReason.STORAGE_LOW))
        assertEquals(StopReason.CAP, GuardChain.stopReasonFor(PauseReason.CAP_REACHED))
    }
}
