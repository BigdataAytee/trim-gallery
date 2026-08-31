package app.trimgallery.core.pipeline.night

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryPolicyTest {

    @Test
    fun `the backoff is the one ARCHITECTURE md section 13 fixes`() {
        assertEquals(listOf(5_000L, 15_000L, 60_000L), RetryPolicy.BACKOFF_MS)
        assertEquals(5_000L, RetryPolicy.delayFor(0))
        assertEquals(15_000L, RetryPolicy.delayFor(1))
        assertEquals(60_000L, RetryPolicy.delayFor(2))
    }

    @Test
    fun `after three attempts the file waits for another night`() {
        // A codec that is still gone after 80 seconds is not coming back soon, and the
        // queue has other work.
        assertNull(RetryPolicy.delayFor(3))
        assertTrue(RetryPolicy.exhausted(3))
        assertTrue(!RetryPolicy.exhausted(2))
    }

    @Test
    fun `the delays grow, because the thing being waited for is a person`() {
        // The codec was reclaimed because something in the foreground wanted it — a
        // camera, a video call — and those last seconds to minutes.
        RetryPolicy.BACKOFF_MS.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
    }
}
