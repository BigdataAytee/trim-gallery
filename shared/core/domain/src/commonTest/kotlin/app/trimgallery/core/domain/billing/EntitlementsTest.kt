package app.trimgallery.core.domain.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A paywall that is wrong is a broken promise or a lost sale. These tests are mostly about
 * the promises.
 */
class EntitlementsTest {

    private val now = Instant.parse("2026-08-30T12:00:00Z")
    private val gb = 1024L * 1024 * 1024

    @Test
    fun `restore is never gated, by anything`() {
        // MONETIZATION.md says this twice, because it is the promise the product rests on.
        assertTrue(Entitlements.mayRestore())
    }

    @Test
    fun `indexing continues after the cap is hit`() {
        // Only optimisation pauses; the gallery keeps getting better either way.
        Tier.entries.forEach { assertTrue(Entitlements.mayIndex(it)) }
        assertTrue(Entitlements.capReached(Tier.FREE, freedThisMonth = 3 * gb))
        assertTrue(Entitlements.mayIndex(Tier.FREE))
    }

    @Test
    fun `the free tier stops at three gigabytes a month`() {
        assertEquals(3 * gb, Entitlements.remainingBytes(Tier.FREE, freedThisMonth = 0))
        assertEquals(gb, Entitlements.remainingBytes(Tier.FREE, freedThisMonth = 2 * gb))
        assertEquals(0, Entitlements.remainingBytes(Tier.FREE, freedThisMonth = 3 * gb))
    }

    @Test
    fun `going over the cap never reports a negative allowance`() {
        // A file can finish slightly over the estimate; that must not read as "-200 MB".
        assertEquals(0, Entitlements.remainingBytes(Tier.FREE, freedThisMonth = 5 * gb))
    }

    @Test
    fun `Pro has no cap`() {
        assertFalse(Entitlements.capReached(Tier.PRO, freedThisMonth = 900 * gb))
        assertTrue(Entitlements.mayOptimise(Tier.PRO, freedThisMonth = 900 * gb, estimatedSaving = 10 * gb))
    }

    @Test
    fun `a first file larger than the whole allowance is still allowed`() {
        // Otherwise a user whose first video would save 4 GB never gets to use the free
        // tier at all — a worse first run than going over the cap once.
        assertTrue(Entitlements.mayOptimise(Tier.FREE, freedThisMonth = 0, estimatedSaving = 4 * gb))
    }

    @Test
    fun `once inside the month, a file that would blow the cap waits`() {
        assertTrue(Entitlements.mayOptimise(Tier.FREE, freedThisMonth = 2 * gb, estimatedSaving = gb))
        assertFalse(Entitlements.mayOptimise(Tier.FREE, freedThisMonth = 2 * gb, estimatedSaving = 2 * gb))
    }

    @Test
    fun `Compress now is five a day on free and unlimited on Pro`() {
        assertTrue(Entitlements.mayCompressNow(Tier.FREE, usedToday = 4))
        assertFalse(Entitlements.mayCompressNow(Tier.FREE, usedToday = 5))
        assertTrue(Entitlements.mayCompressNow(Tier.PRO, usedToday = 500))
    }

    @Test
    fun `every Pro feature is off for free and on for Pro`() {
        ProFeature.entries.forEach { feature ->
            assertFalse(Entitlements.allows(Tier.FREE, feature), "$feature leaked to free")
            assertTrue(Entitlements.allows(Tier.PRO, feature), "$feature withheld from Pro")
        }
    }

    @Test
    fun `retention is clamped to the tier rather than rejected`() {
        // A lapsed Pro user gets 7 days, not an error.
        assertEquals(7, Entitlements.retentionDays(Tier.FREE, requested = 30))
        assertEquals(7, Entitlements.retentionDays(Tier.FREE, requested = 7))
        assertEquals(3, Entitlements.retentionDays(Tier.FREE, requested = 3))
        assertEquals(90, Entitlements.retentionDays(Tier.PRO, requested = 365))
        assertEquals(1, Entitlements.retentionDays(Tier.PRO, requested = 0))
    }

    @Test
    fun `the offer never interrupts the viewer`() {
        assertFalse(Entitlements.mayShowOffer(Tier.FREE, lastShown = null, now = now, inViewer = true))
    }

    @Test
    fun `the offer is shown at most once a week`() {
        assertTrue(Entitlements.mayShowOffer(Tier.FREE, lastShown = null, now = now, inViewer = false))
        assertFalse(
            Entitlements.mayShowOffer(Tier.FREE, lastShown = now - 3.days, now = now, inViewer = false),
        )
        assertTrue(
            Entitlements.mayShowOffer(Tier.FREE, lastShown = now - 8.days, now = now, inViewer = false),
        )
    }

    @Test
    fun `Pro users are never shown the offer`() {
        assertFalse(Entitlements.mayShowOffer(Tier.PRO, lastShown = null, now = now, inViewer = false))
    }
}
