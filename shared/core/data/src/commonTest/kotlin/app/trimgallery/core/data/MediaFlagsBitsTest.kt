package app.trimgallery.core.data

import app.trimgallery.core.model.MediaFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bitmask is the one place a single wrong constant would hide the user's photos: bit
 * 128 is `hidden`, and every gallery query filters on it.
 */
class MediaFlagsBitsTest {

    @Test
    fun `the bits are the ones SCHEMA md fixes`() {
        assertEquals(1L, MediaFlagsBits.HDR)
        assertEquals(2L, MediaFlagsBits.MOTION_PHOTO)
        assertEquals(4L, MediaFlagsBits.ULTRA_HDR)
        assertEquals(8L, MediaFlagsBits.LIVE_PHOTO)
        assertEquals(16L, MediaFlagsBits.RAW)
        assertEquals(32L, MediaFlagsBits.IN_CLOUD_ONLY)
        assertEquals(64L, MediaFlagsBits.FAVOURITE)
        assertEquals(128L, MediaFlagsBits.HIDDEN)
    }

    @Test
    fun `every combination survives a round trip`() {
        // 256 combinations is cheap and exhaustive, and exhaustive is what this needs: a
        // pair of transposed constants would pass any sampled test.
        (0L until 256L).forEach { bits ->
            assertEquals(bits, MediaFlagsBits.encode(MediaFlagsBits.decode(bits)), "bits $bits")
        }
    }

    @Test
    fun `each flag decodes to itself and nothing else`() {
        assertTrue(MediaFlagsBits.decode(128L).hidden)
        assertTrue(!MediaFlagsBits.decode(128L).favourite)
        assertTrue(MediaFlagsBits.decode(64L).favourite)
        assertTrue(!MediaFlagsBits.decode(64L).hidden)
        assertTrue(MediaFlagsBits.decode(1L).hdr)
        assertEquals(MediaFlags(), MediaFlagsBits.decode(0L))
    }

    @Test
    fun `the queries the gallery runs agree with the constants`() {
        // TrimDatabase.sq filters the grid with `(flags & 128) = 0` and Favourites with
        // `(flags & 64) != 0`. If either constant moved, those queries would silently
        // select the wrong photos rather than fail.
        val hidden = MediaFlagsBits.encode(MediaFlags(hidden = true))
        val favourite = MediaFlagsBits.encode(MediaFlags(favourite = true))
        assertEquals(0L, favourite and 128L, "a favourite must not read as hidden")
        assertEquals(128L, hidden and 128L)
        assertEquals(64L, favourite and 64L)
    }

    @Test
    fun `unknown high bits are ignored rather than corrupting the known ones`() {
        // A future migration may add a flag. An old build must still read the ones it
        // knows, not refuse the row.
        val withFutureFlag = 256L or 64L
        assertTrue(MediaFlagsBits.decode(withFutureFlag).favourite)
        assertTrue(!MediaFlagsBits.decode(withFutureFlag).hidden)
    }
}
