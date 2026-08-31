package app.trimgallery.core.domain.space

import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.MediaKind
import app.trimgallery.core.model.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUILD.md § 3 names battery complaints as the risk most likely to sink the app. This
 * number is the defence, so it has to be honest in the direction that costs the app rather
 * than the user.
 */
class EnergyEstimateTest {

    private fun clip(width: Int = 3840, height: Int = 2160) = MediaItem(
        id = "a",
        platformRef = MediaRef("ref"),
        name = "clip.mp4",
        kind = MediaKind.VIDEO,
        codec = "avc1",
        width = width,
        height = height,
        fps = 30.0,
        bitrate = 40_000_000,
        size = 500_000_000,
        duration = 300_000,
        takenAt = null,
        location = null,
        cameraModel = null,
        phash = null,
        sha256 = null,
        mtime = 0,
    )

    @Test
    fun `a minute of 4K matches the bench figure for the chip`() {
        val wh = EnergyEstimate.forEncode(clip(), elapsedMs = 60_000, chipFamily = "midrange")
        assertEquals(0.085, wh, 1e-9)
    }

    @Test
    fun `an unknown chip is charged at the pessimistic rate`() {
        // Under-reporting is a claim the user's own battery graph can contradict, and once
        // they catch the app being optimistic about battery they have no reason to believe
        // the space figures either.
        val unknown = EnergyEstimate.forEncode(clip(), 60_000, chipFamily = "some-new-soc")
        val entry = EnergyEstimate.forEncode(clip(), 60_000, chipFamily = "entry")
        val flagship = EnergyEstimate.forEncode(clip(), 60_000, chipFamily = "flagship")
        assertEquals(entry, unknown)
        assertTrue(unknown > flagship)
    }

    @Test
    fun `a null chip family is treated the same way`() {
        assertEquals(
            EnergyEstimate.forEncode(clip(), 60_000, "entry"),
            EnergyEstimate.forEncode(clip(), 60_000, null),
        )
    }

    @Test
    fun `energy scales with pixels`() {
        val fourK = EnergyEstimate.forEncode(clip(3840, 2160), 60_000, "midrange")
        val tenEighty = EnergyEstimate.forEncode(clip(1920, 1080), 60_000, "midrange")
        assertEquals(fourK / 4, tenEighty, 1e-9)
    }

    @Test
    fun `energy is measured on time actually spent, not on clip length`() {
        // An encode that ran at four times real time cost a quarter of what its length
        // suggests, and the point of this figure is that the user can check it.
        val fast = EnergyEstimate.forEncode(clip(), elapsedMs = 15_000, chipFamily = "midrange")
        val slow = EnergyEstimate.forEncode(clip(), elapsedMs = 60_000, chipFamily = "midrange")
        assertEquals(slow / 4, fast, 1e-9)
    }

    @Test
    fun `an encode that took no time costs nothing`() {
        assertEquals(0.0, EnergyEstimate.forEncode(clip(), 0, "midrange"))
        assertEquals(0.0, EnergyEstimate.forEncode(clip(), -5, "midrange"))
    }

    @Test
    fun `the figure is stated coarsely, because the table is not precise`() {
        // "about 2.73 Wh" claims a precision a bench table across chip families cannot have.
        assertEquals("about 3 Wh", EnergyEstimate.describe(2.73))
        assertEquals("about 2 Wh", EnergyEstimate.describe(2.4))
        assertEquals("under 1 Wh", EnergyEstimate.describe(0.4))
        assertEquals("no energy used", EnergyEstimate.describe(0.0))
    }

    @Test
    fun `the battery share is what people actually reason about`() {
        // A whole night's work against a mid-range battery.
        assertEquals(19, EnergyEstimate.asBatteryPercent(3.0))
        // Below one per cent reads as a rounding error rather than as good news.
        assertNull(EnergyEstimate.asBatteryPercent(0.05))
        assertNull(EnergyEstimate.asBatteryPercent(0.0))
    }

    @Test
    fun `a night at the cap is a few per cent of a charge`() {
        // The claim the whole feature rests on: an hour of encoding does not flatten a
        // phone. If this ever fails, the copy on the Space screen has to change.
        val hour = EnergyEstimate.forEncode(clip(), elapsedMs = 60 * 60_000, chipFamily = "midrange")
        val percent = EnergyEstimate.asBatteryPercent(hour)!!
        assertTrue(percent in 1..50, "an hour of 4K costs $percent% of a charge")
    }
}
