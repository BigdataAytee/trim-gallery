package app.trimgallery.core.domain.settings

import app.trimgallery.core.domain.billing.Entitlements
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.PhotoFormat
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.core.model.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsPolicyTest {

    private val everything = Settings(
        qualityTarget = QualityTarget.COMPACT,
        photoReversible = true,
        allowAv1 = true,
        carefulVerify = true,
        undoRetentionDays = 90,
    )

    @Test
    fun `defaults survive sanitising untouched`() {
        assertEquals(Settings(), SettingsPolicy.sanitise(Settings(), Tier.PRO))
    }

    /**
     * BUILD.md § 6 gives the "Free space" folder mode a default of 30 days; MONETIZATION.md
     * gives the free tier 7. Both are true and they meet here: the *setting's* default is
     * 30, and a free user's copy of it is 7 until they buy Pro (recorded in PROJECT.md).
     * The consequence the UI has to respect is that it must show the sanitised number, not
     * the stored one, or it will promise a free user 30 days of originals it deletes at 7.
     */
    @Test
    fun `the shipped retention default is clamped for a free user`() {
        assertEquals(30, Settings().undoRetentionDays)
        assertEquals(
            Entitlements.FREE_RETENTION_DAYS,
            SettingsPolicy.sanitise(Settings(), Tier.FREE).undoRetentionDays,
        )
        assertEquals(30, SettingsPolicy.sanitise(Settings(), Tier.PRO).undoRetentionDays)
    }

    @Test
    fun `Pro keeps everything it turned on`() {
        assertEquals(everything, SettingsPolicy.sanitise(everything, Tier.PRO))
    }

    /**
     * A lapsed Pro user gets clamped settings, not a broken app. Every one of these is a
     * value the free tier cannot honour, and leaving it stored would mean the night pass
     * quietly using an entitlement nobody paid for.
     */
    @Test
    fun `free tier is clamped to what it may have`() {
        val clamped = SettingsPolicy.sanitise(everything, Tier.FREE)
        assertEquals(QualityTarget.STANDARD, clamped.qualityTarget)
        assertFalse(clamped.photoReversible)
        assertFalse(clamped.allowAv1)
        assertFalse(clamped.carefulVerify)
        assertEquals(Entitlements.FREE_RETENTION_DAYS, clamped.undoRetentionDays)
    }

    @Test
    fun `the nightly cap is clamped to a range that can finish a file`() {
        assertEquals(
            SettingsPolicy.MIN_CAP_MINUTES,
            SettingsPolicy.sanitise(Settings(nightlyCapMinutes = 0), Tier.PRO).nightlyCapMinutes,
        )
        assertEquals(
            SettingsPolicy.MAX_CAP_MINUTES,
            SettingsPolicy.sanitise(Settings(nightlyCapMinutes = 10_000), Tier.PRO).nightlyCapMinutes,
        )
        assertEquals(90, SettingsPolicy.sanitise(Settings(nightlyCapMinutes = 90), Tier.PRO).nightlyCapMinutes)
    }

    /**
     * The read side's `runCatching { LocalTime.parse(it) }.getOrNull()` should never have
     * anything to catch: a value that cannot be honoured is not stored.
     */
    @Test
    fun `an unparseable stop-by time never reaches the store`() {
        for (bad in listOf("25:00", "7:00", "07:60", "seven", "", "07:00:00")) {
            assertNull(SettingsPolicy.sanitise(Settings(stopByTime = bad), Tier.PRO).stopByTime, bad)
            assertFalse(SettingsPolicy.isValidStopBy(bad), bad)
        }
        for (good in listOf("00:00", "06:30", "23:59")) {
            assertEquals(good, SettingsPolicy.sanitise(Settings(stopByTime = good), Tier.PRO).stopByTime)
            assertTrue(SettingsPolicy.isValidStopBy(good), good)
        }
        assertTrue(SettingsPolicy.isValidStopBy(null))
    }

    @Test
    fun `sanitising is idempotent`() {
        val once = SettingsPolicy.sanitise(everything.copy(nightlyCapMinutes = 9_999, stopByTime = "nope"), Tier.FREE)
        assertEquals(once, SettingsPolicy.sanitise(once, Tier.FREE))
    }

    @Test
    fun `free tier sees the locked switches rather than nothing`() {
        val locked = SettingsPolicy.lockedFor(Tier.FREE)
        assertEquals(SettingsPolicy.Locked.entries.toSet(), locked)
        for (item in locked) assertTrue(SettingsPolicy.lockExplanation(item).isNotBlank(), "$item")
        assertTrue(SettingsPolicy.lockedFor(Tier.PRO).isEmpty())
    }

    @Test
    fun `changing the quality target or AV1 makes triage stale`() {
        assertTrue(SettingsPolicy.invalidatesTriage(Settings(), Settings(qualityTarget = QualityTarget.COMPACT)))
        assertTrue(SettingsPolicy.invalidatesTriage(Settings(), Settings(allowAv1 = true)))
    }

    /** Re-triaging a whole library to learn that photos will be HEIC is a night spent on nothing. */
    @Test
    fun `changing the photo format does not make triage stale`() {
        assertFalse(SettingsPolicy.invalidatesTriage(Settings(), Settings(photoFormat = PhotoFormat.HEIC)))
        assertFalse(SettingsPolicy.invalidatesTriage(Settings(), Settings(undoRetentionDays = 14)))
    }

    @Test
    fun `scheduling constraints are re-sent when they change`() {
        assertTrue(SettingsPolicy.requiresReschedule(Settings(), Settings(startWhenFull = false)))
        assertTrue(SettingsPolicy.requiresReschedule(Settings(), Settings(keepWorkingWhileUsing = true)))
        assertTrue(SettingsPolicy.requiresReschedule(Settings(), Settings(stopByTime = "06:00")))
        assertFalse(SettingsPolicy.requiresReschedule(Settings(), Settings(photoFormat = PhotoFormat.HEIC)))
    }

    @Test
    fun `Compact warns once, on the way in`() {
        val compact = Settings(qualityTarget = QualityTarget.COMPACT)
        assertEquals(listOf(SettingsPolicy.COMPACT_WARNING), SettingsPolicy.notices(Settings(), compact))
        assertTrue(SettingsPolicy.notices(compact, compact).isEmpty())
        assertTrue(SettingsPolicy.notices(compact, Settings()).isEmpty())
    }

    @Test
    fun `turning face clustering off says what happens to the data`() {
        val notices = SettingsPolicy.notices(Settings(), Settings(faceClusteringEnabled = false))
        assertEquals(1, notices.size)
        assertTrue(notices.single().contains("ever left this phone"), notices.single())
    }

    /** Entitlements keeps existing undo rows on the expiry they were created with. */
    @Test
    fun `shortening retention says it applies to new originals only`() {
        val notices = SettingsPolicy.notices(Settings(undoRetentionDays = 30), Settings(undoRetentionDays = 7))
        assertEquals(1, notices.size)
        assertTrue(notices.single().contains("already in the bin"))
        assertTrue(SettingsPolicy.notices(Settings(undoRetentionDays = 7), Settings(undoRetentionDays = 30)).isEmpty())
    }

    @Test
    fun `nothing changed says nothing`() {
        assertTrue(SettingsPolicy.notices(everything, everything).isEmpty())
    }
}
