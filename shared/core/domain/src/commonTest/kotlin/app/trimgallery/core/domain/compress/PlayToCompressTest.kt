package app.trimgallery.core.domain.compress

import app.trimgallery.core.domain.compress.PlayToCompress.Effect
import app.trimgallery.core.domain.compress.PlayToCompress.Event
import app.trimgallery.core.domain.compress.PlayToCompress.Reason
import app.trimgallery.core.domain.compress.PlayToCompress.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlayToCompressTest {

    /** A 2-second, 30 fps clip: 60 frames, 33_333 µs apart. */
    private val source = PlayToCompress.Source(durationMs = 2_000, fps = 30.0)

    private fun tap() = PlayToCompress(source)

    private fun frameAt(i: Int) = Event.Frame((i * 1_000_000L) / 30)

    /** Plays [count] frames from the start and returns the effects the frames produced. */
    private fun PlayToCompress.play(count: Int): List<Effect> {
        on(Event.Play)
        return (0 until count).map { on(frameAt(it)) }
    }

    @Test
    fun `an uninterrupted playthrough produces a complete file`() {
        val tap = tap()
        assertEquals(Effect.START, tap.on(Event.Play))
        repeat(60) { assertEquals(Effect.FEED, tap.on(frameAt(it))) }
        assertEquals(Effect.FINISH, tap.on(Event.EndOfStream))
        assertEquals(State.Complete(60), tap.state)
        assertEquals(1f, tap.progress)
    }

    @Test
    fun `nothing is fed before the user presses play`() {
        val tap = tap()
        assertEquals(Effect.NONE, tap.on(frameAt(0)))
        assertEquals(State.Armed, tap.state)
        assertEquals(0, tap.frames)
    }

    /**
     * The one that matters most: a user who resumes a half-watched video would otherwise
     * get an encode of the second half, and it would pass every later gate — the output
     * opens, it is smaller, and VMAF sampled against its own timeline looks fine.
     */
    @Test
    fun `playback starting mid-stream never becomes a file`() {
        val tap = tap()
        tap.on(Event.Play)
        assertEquals(Effect.DISCARD, tap.on(frameAt(30)))
        assertEquals(State.Abandoned(Reason.STARTED_MID_STREAM), tap.state)
    }

    @Test
    fun `scrubbing backwards abandons the tap`() {
        val tap = tap()
        tap.play(20)
        assertEquals(Effect.DISCARD, tap.on(Event.SeekTo(0)))
        assertEquals(State.Abandoned(Reason.SEEKED_BACK), tap.state)
    }

    @Test
    fun `skipping forward abandons the tap`() {
        val tap = tap()
        tap.play(20)
        assertEquals(Effect.DISCARD, tap.on(Event.SeekTo(1_500_000)))
        assertEquals(State.Abandoned(Reason.SKIPPED_FORWARD), tap.state)
    }

    /** Players report a seek to the current position after a pause. Nothing moved. */
    @Test
    fun `a seek that does not move is ignored`() {
        val tap = tap()
        tap.play(20)
        val before = tap.state
        assertEquals(Effect.NONE, tap.on(Event.SeekTo(frameAt(19).ptsUs)))
        assertEquals(before, tap.state)
    }

    @Test
    fun `dropped frames abandon the tap rather than shortening the video`() {
        val tap = tap()
        tap.play(10)
        // Six frames missing at 30 fps is 200 ms, past the four-interval tolerance.
        assertEquals(Effect.DISCARD, tap.on(frameAt(16)))
        assertEquals(State.Abandoned(Reason.SKIPPED_FORWARD), tap.state)
    }

    @Test
    fun `one dropped frame is within tolerance`() {
        val tap = tap()
        tap.play(10)
        assertEquals(Effect.FEED, tap.on(frameAt(11)))
    }

    @Test
    fun `a frame delivered twice is not encoded twice`() {
        val tap = tap()
        tap.play(10)
        assertEquals(Effect.NONE, tap.on(frameAt(9)))
        assertEquals(10, tap.frames)
    }

    @Test
    fun `pause and resume keeps the tap alive`() {
        val tap = tap()
        tap.play(30)
        assertEquals(Effect.HOLD, tap.on(Event.Pause))
        assertIs<State.Held>(tap.state)
        assertEquals(Effect.RESUME, tap.on(Event.Play))
        assertEquals(Effect.FEED, tap.on(frameAt(30)))
        repeat(29) { tap.on(frameAt(31 + it)) }
        assertEquals(Effect.FINISH, tap.on(Event.EndOfStream))
    }

    /** A player re-renders the current frame while paused; that is not a new frame. */
    @Test
    fun `a re-rendered frame during a pause is ignored`() {
        val tap = tap()
        tap.play(30)
        tap.on(Event.Pause)
        assertEquals(Effect.NONE, tap.on(frameAt(29)))
        assertIs<State.Held>(tap.state)
    }

    /** But a genuinely new frame while the encoder is held is a gap in the making. */
    @Test
    fun `frames arriving past a held encoder abandon the tap`() {
        val tap = tap()
        tap.play(30)
        tap.on(Event.Pause)
        assertEquals(Effect.DISCARD, tap.on(frameAt(31)))
        assertEquals(State.Abandoned(Reason.SKIPPED_FORWARD), tap.state)
    }

    @Test
    fun `a pause that never ends gives the encoder back`() {
        val tap = tap()
        tap.play(30)
        tap.on(Event.Pause)
        assertEquals(Effect.DISCARD, tap.on(Event.HoldTimeout))
        assertEquals(State.Abandoned(Reason.HELD_TOO_LONG), tap.state)
        assertTrue((tap.state as State.Abandoned).requeueForNight)
    }

    @Test
    fun `leaving the player early discards the partial encode`() {
        val tap = tap()
        tap.play(30)
        assertEquals(Effect.DISCARD, tap.on(Event.Leave))
        assertEquals(State.Abandoned(Reason.LEFT_EARLY), tap.state)
    }

    /**
     * End of stream is not proof of completion — a player emits it whenever it stops. The
     * proof is the last timestamp.
     */
    @Test
    fun `end of stream halfway through is truncation, not completion`() {
        val tap = tap()
        tap.play(30)
        assertEquals(Effect.DISCARD, tap.on(Event.EndOfStream))
        assertEquals(State.Abandoned(Reason.TRUNCATED), tap.state)
    }

    @Test
    fun `heat ends the tap and leaves the file to the night`() {
        val tap = tap()
        tap.play(30)
        assertEquals(Effect.DISCARD, tap.on(Event.Overheated))
        val state = assertIs<State.Abandoned>(tap.state)
        assertEquals(Reason.THERMAL, state.reason)
        assertTrue(state.requeueForNight)
    }

    /** A broken decoder would break the night pass too; that belongs on the Skipped list. */
    @Test
    fun `a decoder error is not requeued`() {
        val tap = tap()
        tap.play(30)
        assertEquals(Effect.DISCARD, tap.on(Event.DecoderError))
        val state = assertIs<State.Abandoned>(tap.state)
        assertEquals(Reason.DECODER_ERROR, state.reason)
        assertFalse(state.requeueForNight)
    }

    /** A player closing after it finished must not throw away a completed file. */
    @Test
    fun `leaving after completion does not discard the finished file`() {
        val tap = tap()
        tap.play(60)
        assertEquals(Effect.FINISH, tap.on(Event.EndOfStream))
        assertEquals(Effect.NONE, tap.on(Event.Leave))
        assertEquals(State.Complete(60), tap.state)
    }

    @Test
    fun `an abandoned tap stays abandoned`() {
        val tap = tap()
        tap.play(10)
        tap.on(Event.SeekTo(0))
        assertEquals(Effect.NONE, tap.on(Event.Play))
        assertEquals(Effect.NONE, tap.on(frameAt(0)))
        assertEquals(Effect.NONE, tap.on(Event.EndOfStream))
        assertEquals(State.Abandoned(Reason.SEEKED_BACK), tap.state)
    }

    @Test
    fun `progress follows the playhead`() {
        val tap = tap()
        tap.play(30)
        assertTrue(tap.progress in 0.45f..0.55f, "was ${tap.progress}")
    }

    /**
     * An unknown frame rate gets a wider tolerance rather than an assumed 30 fps: assuming
     * 30 on a 240 fps slow-motion clip would wave seven dropped frames through.
     */
    @Test
    fun `an unknown frame rate uses the wider tolerance`() {
        val tap = PlayToCompress(PlayToCompress.Source(durationMs = 2_000, fps = null))
        tap.on(Event.Play)
        assertEquals(Effect.FEED, tap.on(Event.Frame(0)))
        assertEquals(Effect.FEED, tap.on(Event.Frame(400_000)))
        assertEquals(Effect.DISCARD, tap.on(Event.Frame(1_000_000)))
    }

    /** A high frame rate would give a four-interval tolerance under 20 ms; the floor holds. */
    @Test
    fun `a high frame rate keeps a usable floor`() {
        val tap = PlayToCompress(PlayToCompress.Source(durationMs = 1_000, fps = 240.0))
        tap.on(Event.Play)
        assertEquals(Effect.FEED, tap.on(Event.Frame(0)))
        assertEquals(Effect.FEED, tap.on(Event.Frame(80_000)))
    }

    /**
     * Container durations and the last frame's timestamp disagree by a frame or two, for
     * the same reason `Verifier` allows 100 ms.
     */
    @Test
    fun `a last frame a few milliseconds short still completes`() {
        val tap = tap()
        // 59 of the 60 frames: the last timestamp is 1.933 s against a stated 2.000 s.
        tap.play(59)
        assertEquals(Effect.FINISH, tap.on(Event.EndOfStream))
        assertEquals(State.Complete(59), tap.state)
    }
}
