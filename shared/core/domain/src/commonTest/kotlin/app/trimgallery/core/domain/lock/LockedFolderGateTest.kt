package app.trimgallery.core.domain.lock

import app.trimgallery.core.domain.lock.LockedFolderGate.Event
import app.trimgallery.core.domain.lock.LockedFolderGate.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LockedFolderGateTest {

    private val now = Instant.parse("2026-08-30T12:00:00Z")

    @Test
    fun `it starts locked`() {
        assertFalse(LockedFolderGate.isUnlocked(State.Locked, now))
    }

    @Test
    fun `opening prompts for authentication`() {
        assertEquals(State.Authenticating, LockedFolderGate.reduce(State.Locked, Event.Open, now))
    }

    @Test
    fun `success opens a bounded session`() {
        val state = LockedFolderGate.reduce(State.Authenticating, Event.Succeeded, now)
        assertIs<State.Unlocked>(state)
        assertEquals(now + LockedFolderGate.SESSION, state.until)
        assertTrue(LockedFolderGate.isUnlocked(state, now))
    }

    @Test
    fun `the session expires on its own`() {
        val unlocked = LockedFolderGate.reduce(State.Authenticating, Event.Succeeded, now)
        val later = now + LockedFolderGate.SESSION + 1.seconds
        assertFalse(LockedFolderGate.isUnlocked(unlocked, later))
        assertEquals(State.Locked, LockedFolderGate.reduce(unlocked, Event.Tick, later))
    }

    @Test
    fun `a tick inside the session changes nothing`() {
        val unlocked = LockedFolderGate.reduce(State.Authenticating, Event.Succeeded, now)
        assertEquals(unlocked, LockedFolderGate.reduce(unlocked, Event.Tick, now + 1.seconds))
    }

    @Test
    fun `backgrounding re-locks from any state`() {
        // The whole point of the folder: putting the phone down must close it.
        val unlocked = LockedFolderGate.reduce(State.Authenticating, Event.Succeeded, now)
        listOf(State.Locked, State.Authenticating, unlocked, State.Failed("x")).forEach { state ->
            assertEquals(State.Locked, LockedFolderGate.reduce(state, Event.Backgrounded, now), "from $state")
        }
    }

    @Test
    fun `a failure is shown, a cancellation is not`() {
        val failed = LockedFolderGate.reduce(State.Authenticating, Event.Failed("Not recognised"), now)
        assertEquals(State.Failed("Not recognised"), failed)
        // Backing out of the prompt is not an error and must not look like one.
        assertEquals(State.Locked, LockedFolderGate.reduce(State.Authenticating, Event.Cancelled, now))
    }

    @Test
    fun `retrying after a failure prompts again`() {
        val failed = State.Failed("Not recognised")
        assertEquals(State.Authenticating, LockedFolderGate.reduce(failed, Event.Open, now))
    }

    @Test
    fun `opening an already unlocked folder does not prompt again`() {
        val unlocked = LockedFolderGate.reduce(State.Authenticating, Event.Succeeded, now)
        assertEquals(unlocked, LockedFolderGate.reduce(unlocked, Event.Open, now + 1.seconds))
    }

    @Test
    fun `opening after the session lapsed prompts again`() {
        val unlocked = LockedFolderGate.reduce(State.Authenticating, Event.Succeeded, now)
        val later = now + LockedFolderGate.SESSION + 1.seconds
        assertEquals(State.Authenticating, LockedFolderGate.reduce(unlocked, Event.Open, later))
    }

    @Test
    fun `a second open while the prompt is up does not restart it`() {
        assertEquals(
            State.Authenticating,
            LockedFolderGate.reduce(State.Authenticating, Event.Open, now),
        )
    }

    @Test
    fun `the session is short enough to matter`() {
        // A long session would defeat the feature the first time the user handed the
        // phone over to show someone a photo.
        assertTrue(LockedFolderGate.SESSION <= 300.seconds, "${LockedFolderGate.SESSION}")
    }
}
