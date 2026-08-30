package app.trimgallery.core.domain.compress

import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.MediaStatus
import app.trimgallery.core.model.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompressNowTest {

    private fun item(
        kind: MediaKind = MediaKind.VIDEO,
        size: Long = 380L * 1024 * 1024,
        duration: Long? = 120_000,
        status: MediaStatus = MediaStatus.CANDIDATE,
        skipReason: SkipReason? = null,
        optimisedAt: Long? = null,
        estSaving: Long? = null,
    ) = MediaItem(
        id = "m1",
        platformRef = MediaRef("content://x"),
        name = "clip.mp4",
        kind = kind,
        codec = "h264",
        width = 3840,
        height = 2160,
        fps = 30.0,
        bitrate = 40_000_000,
        size = size,
        duration = duration,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        status = status,
        skipReason = skipReason,
        mtime = 0,
        estSaving = estSaving,
        optimisedAt = optimisedAt,
    )

    private fun allowed(d: CompressNow.Decision) = assertIs<CompressNow.Decision.Allowed>(d)
    private fun refused(d: CompressNow.Decision) = assertIs<CompressNow.Decision.Refused>(d)

    @Test
    fun `an ordinary video is allowed on battery`() {
        val d = allowed(CompressNow.decide(item(), Tier.FREE, usedToday = 0))
        assertTrue(d.mayWatchWhileWorking)
        assertTrue(d.showBatteryNote)
        assertFalse(d.unlikelyToHelp)
    }

    @Test
    fun `the battery note is shown once`() {
        val d = allowed(CompressNow.decide(item(), Tier.FREE, usedToday = 0, batteryNoteSeen = true))
        assertFalse(d.showBatteryNote)
    }

    @Test
    fun `free tier gets five a day and Pro is unlimited`() {
        allowed(CompressNow.decide(item(), Tier.FREE, usedToday = 4))
        val blocked = refused(CompressNow.decide(item(), Tier.FREE, usedToday = 5))
        assertEquals(CompressNow.Refusal.DAILY_LIMIT_REACHED, blocked.refusal)
        assertTrue(blocked.offerPro)
        allowed(CompressNow.decide(item(), Tier.PRO, usedToday = 99))
    }

    /**
     * The generational-loss rule. Five times a day, on the files the user cares about most,
     * is exactly how a "visually lossless" app ends up with visible artefacts.
     */
    @Test
    fun `a file Trim already optimised cannot be optimised again`() {
        val d = refused(CompressNow.decide(item(optimisedAt = 1_700_000_000_000), Tier.PRO, usedToday = 0))
        assertEquals(CompressNow.Refusal.ALREADY_OPTIMISED, d.refusal)
        assertFalse(d.offerPro)
    }

    @Test
    fun `formats that would lose data are refused even to a paying user`() {
        for (reason in listOf(
            SkipReason.HDR,
            SkipReason.MOTION_PHOTO,
            SkipReason.ULTRA_HDR,
            SkipReason.LIVE_PHOTO,
            SkipReason.RAW,
        )) {
            val d = refused(CompressNow.decide(item(skipReason = reason), Tier.PRO, usedToday = 0))
            assertEquals(CompressNow.Refusal.WOULD_LOSE_DATA, d.refusal, "$reason")
        }
    }

    @Test
    fun `a file the search already failed on is not offered a second identical attempt`() {
        val d = refused(
            CompressNow.decide(item(skipReason = SkipReason.COULD_NOT_REACH_QUALITY), Tier.PRO, usedToday = 0),
        )
        assertEquals(CompressNow.Refusal.QUALITY_UNREACHABLE, d.refusal)
    }

    @Test
    fun `a cloud-only file says so rather than failing`() {
        val d = refused(CompressNow.decide(item(skipReason = SkipReason.IN_CLOUD_ONLY), Tier.PRO, usedToday = 0))
        assertEquals(CompressNow.Refusal.NOT_DOWNLOADED, d.refusal)
    }

    @Test
    fun `a job already running is not started twice`() {
        val d = refused(CompressNow.decide(item(status = MediaStatus.PROCESSING), Tier.PRO, usedToday = 0))
        assertEquals(CompressNow.Refusal.ALREADY_RUNNING, d.refusal)
    }

    /**
     * The split this class exists for: "not worth it" is triage's judgement, and a user
     * standing in front of the file may disagree. They are warned, not refused.
     */
    @Test
    fun `not worth it is a warning, not a refusal`() {
        for (reason in listOf(
            SkipReason.ALREADY_EFFICIENT,
            SkipReason.TOO_SMALL,
            SkipReason.WOULD_NOT_SHRINK,
        )) {
            val d = allowed(CompressNow.decide(item(skipReason = reason), Tier.FREE, usedToday = 0))
            assertTrue(d.unlikelyToHelp, "$reason")
        }
    }

    @Test
    fun `a file that is not media at all is refused`() {
        val d = refused(CompressNow.decide(item(kind = MediaKind.FILE), Tier.PRO, usedToday = 0))
        assertEquals(CompressNow.Refusal.NOT_SUPPORTED, d.refusal)
    }

    /**
     * Item facts are checked before the paywall, so a user is never sold Pro for a button
     * that would still do nothing (MONETIZATION.md § Conversion moments, "no dark patterns").
     */
    @Test
    fun `an impossible file out of daily runs is refused for the file, not for the tier`() {
        val d = refused(CompressNow.decide(item(skipReason = SkipReason.RAW), Tier.FREE, usedToday = 5))
        assertEquals(CompressNow.Refusal.WOULD_LOSE_DATA, d.refusal)
        assertFalse(d.offerPro)
    }

    @Test
    fun `a photo may be compressed but has nothing to watch`() {
        val d = allowed(CompressNow.decide(item(kind = MediaKind.PHOTO, duration = null), Tier.FREE, usedToday = 0))
        assertFalse(d.mayWatchWhileWorking)
    }

    @Test
    fun `the sheet shows a saving only when something measured one`() {
        val blind = allowed(CompressNow.decide(item(estSaving = null), Tier.FREE, usedToday = 0))
        assertNull(blind.estimate.expectedSaving)
        assertNull(blind.estimate.expectedNewSize)

        val known = allowed(CompressNow.decide(item(estSaving = 215L * 1024 * 1024), Tier.FREE, usedToday = 0))
        assertEquals(165L * 1024 * 1024, known.estimate.expectedNewSize)
    }

    @Test
    fun `the sheet shows a time only when this device's speed is known`() {
        val blind = allowed(CompressNow.decide(item(), Tier.FREE, usedToday = 0))
        assertNull(blind.estimate.expectedMs)

        val known = allowed(CompressNow.decide(item(), Tier.FREE, usedToday = 0, realtimeMultiple = 4.0))
        assertEquals(30_000, known.estimate.expectedMs)
    }

    @Test
    fun `a video with no known duration gets no time estimate`() {
        val d = allowed(CompressNow.decide(item(duration = null), Tier.FREE, usedToday = 0, realtimeMultiple = 4.0))
        assertNull(d.estimate.expectedMs)
    }

    /** USER_JOURNEY.md § 6 verbatim: "Now 165 MB (was 380 MB)". */
    @Test
    fun `the result line matches the journey`() {
        assertEquals(
            "Now 165 MB (was 380 MB)",
            CompressNow.describeResult(380L * 1024 * 1024, 165L * 1024 * 1024),
        )
    }

    @Test
    fun `only two of the three endings write to the library`() {
        assertFalse(CompressNow.Finish.SHARE.writesToLibrary)
        assertTrue(CompressNow.Finish.REPLACE_ORIGINAL.writesToLibrary)
        assertTrue(CompressNow.Finish.KEEP_BOTH.writesToLibrary)
    }

    @Test
    fun `every refusal has a sentence`() {
        for (refusal in CompressNow.Refusal.entries) {
            val text = CompressNow.explain(refusal)
            assertTrue(text.isNotBlank(), "$refusal")
            assertTrue(text.trim().endsWith("."), "$refusal should be a sentence: $text")
        }
    }
}
