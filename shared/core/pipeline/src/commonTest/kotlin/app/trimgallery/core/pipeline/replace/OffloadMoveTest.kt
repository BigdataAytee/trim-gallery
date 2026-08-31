package app.trimgallery.core.pipeline.replace

import app.trimgallery.core.model.MediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The one ordering that separates "offload" from "delete": copy, verify, *then* remove.
 *
 * Every test here asserts the same thing from a different angle — that the source delete
 * is unreachable except through a verification that returned true.
 */
class OffloadMoveTest {

    private val source = MediaRef("content://tree/DCIM/VID_0001.mp4")
    private val destination = MediaRef("content://sdcard/TrimOriginals")
    private val landed = MediaRef("content://sdcard/TrimOriginals/VID_0001.mp4")

    private class FakeOps(
        val calls: MutableList<String> = mutableListOf(),
        private val copyFails: Boolean = false,
        private val verifyResult: Boolean = true,
        private val verifyThrows: Throwable? = null,
        private val removeSourceFails: Boolean = false,
    ) : OffloadMove.Ops {
        var sourceRemoved = false
        var copyRemoved = false

        override suspend fun copy(source: MediaRef, destination: MediaRef): MediaRef {
            calls += "copy"
            if (copyFails) error("card is full")
            return MediaRef("content://sdcard/TrimOriginals/VID_0001.mp4")
        }

        override suspend fun verify(source: MediaRef, copy: MediaRef): Boolean {
            calls += "verify"
            verifyThrows?.let { throw it }
            return verifyResult
        }

        override suspend fun removeCopy(copy: MediaRef) {
            calls += "removeCopy"
            copyRemoved = true
        }

        override suspend fun removeSource(source: MediaRef) {
            calls += "removeSource"
            if (removeSourceFails) error("the grant went stale")
            sourceRemoved = true
        }
    }

    @Test
    fun `a good move copies, verifies, then removes the source in that order`() = runTest {
        val ops = FakeOps()
        val outcome = OffloadMove(ops).move(source, destination)

        assertEquals(OffloadMove.Outcome.Moved(landed), outcome)
        assertEquals(listOf("copy", "verify", "removeSource"), ops.calls)
    }

    @Test
    fun `a failed copy never touches the source`() = runTest {
        // The commonest real failure: the card filled up, or was pulled out mid-write.
        val ops = FakeOps(copyFails = true)
        val outcome = OffloadMove(ops).move(source, destination)

        assertIs<OffloadMove.Outcome.Failed>(outcome)
        assertTrue(!ops.sourceRemoved)
        assertEquals(listOf("copy"), ops.calls)
    }

    @Test
    fun `a copy that does not match the original is deleted, and the source is kept`() = runTest {
        // Counterfeit cards silently drop data past their real capacity; the copy exists,
        // reports a size, and is wrong. This is the case that would lose the photograph.
        val ops = FakeOps(verifyResult = false)
        val outcome = OffloadMove(ops).move(source, destination)

        assertIs<OffloadMove.Outcome.Failed>(outcome)
        assertTrue(!ops.sourceRemoved, "an unverified copy must never authorise a delete")
        assertTrue(ops.copyRemoved)
        assertEquals(listOf("copy", "verify", "removeCopy"), ops.calls)
    }

    @Test
    fun `a verification that throws is treated as a failed verification`() = runTest {
        val ops = FakeOps(verifyThrows = IllegalStateException("card removed"))
        val outcome = OffloadMove(ops).move(source, destination)

        assertIs<OffloadMove.Outcome.Failed>(outcome)
        assertTrue(!ops.sourceRemoved)
        assertTrue(ops.copyRemoved)
    }

    @Test
    fun `cancellation during verification cleans up and never deletes the source`() = runTest {
        val ops = FakeOps(verifyThrows = CancellationException("unplugged"))
        var thrown = false
        try {
            OffloadMove(ops).move(source, destination)
        } catch (expected: CancellationException) {
            thrown = true
        }

        assertTrue(thrown, "the cancellation must not be swallowed")
        assertTrue(!ops.sourceRemoved)
        assertTrue(ops.copyRemoved, "a half-written file must not be left on the card")
    }

    @Test
    fun `a verified copy is kept even when the source cannot be removed`() = runTest {
        // Two copies is untidy. Deleting the one copy known to be intact is not.
        val ops = FakeOps(removeSourceFails = true)
        val outcome = OffloadMove(ops).move(source, destination)

        val failed = assertIs<OffloadMove.Outcome.Failed>(outcome)
        assertTrue(failed.reason.contains("safe on the offload volume"), failed.reason)
        assertTrue(!ops.copyRemoved)
    }
}
