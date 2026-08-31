package app.trimgallery.core.domain.compress

/**
 * Play-to-compress: the decoder tap, as a state machine (BUILD.md § 9).
 *
 * > In the built-in player, when the user chooses Compress now and presses play, the
 * > decoder output is teed into the encoder so the compressed file is ready when playback
 * > ends. Only path allowed on battery.
 *
 * The idea is free real-time encoding — the frames are being decoded anyway, so feeding
 * them to the encoder costs one hardware encode and no second decode. The danger is the
 * exact opposite of free: a player is a thing the user *steers*. They pause, they scrub
 * back to watch a bit again, they skip the boring middle, they leave. Every one of those
 * makes the stream arriving at the encoder no longer a faithful copy of the source, and an
 * encoder does not know that. It will happily produce a file — a shorter one, or one
 * missing the middle — and that file would then go through verify and replace looking like
 * a success.
 *
 * So this machine exists to hold one rule:
 *
 * **A tap either delivers every frame, in order, from the first to the last, or it
 * delivers nothing at all.**
 *
 * Nothing partial is ever finished. When the tap breaks, the temp file is discarded and the
 * item goes back to the night queue, where it will be encoded properly from a decode that
 * nobody is steering. The cost of being wrong in that direction is one wasted encode; the
 * cost of being wrong in the other direction is a user's video with a hole in it.
 *
 * This is deliberately platform-free. Android tees frames off a Transformer effect on the
 * decoder surface and iOS off `AVPlayerItemVideoOutput` (ARCHITECTURE.md § 5), but *when to
 * give up* is a product decision and identical on both, so both get it from here.
 *
 * A completed tap is not a finished job. It has produced a temp file, exactly like a night
 * encode, and it goes through the same `VerifyPass` and the same `Replacer` afterwards —
 * play-to-compress skips no gate (safe-replace: *"Replacing before verification"* is on the
 * list of things that must never appear).
 */
class PlayToCompress(private val source: Source) {

    /** What the machine needs to know about the clip to police the frames it sees. */
    data class Source(
        val durationMs: Long,
        /**
         * Frames per second, or null when the container does not say.
         *
         * Only used to size the gap tolerance below. Unknown fps gets [UNKNOWN_FPS_GAP_US]
         * rather than an assumed 30, because an assumed 30 on a 240 fps slow-motion clip
         * would let seven dropped frames through as normal.
         */
        val fps: Double? = null,
    )

    sealed interface State {
        /** The user chose Compress now; playback has not started. */
        data object Armed : State

        /** Frames are flowing into the encoder. */
        data class Recording(val frames: Int, val lastPtsUs: Long) : State

        /** Playback is paused. The encoder session is held open, but not forever. */
        data class Held(val frames: Int, val lastPtsUs: Long) : State

        /** Every frame arrived. The temp file is complete and goes to verify. */
        data class Complete(val frames: Int) : State

        /** The tap broke. Nothing is committed; see [Abandoned.requeueForNight]. */
        data class Abandoned(val reason: Reason) : State {
            /**
             * Whether the night pass should pick this file up.
             *
             * Everything except a decoder failure: the user asked for this file to be
             * optimised, and a pause or a scrub is not them changing their mind. A decoder
             * error is different — the night pass would hit the same broken file, and the
             * honest place for it is the Skipped list with a reason.
             */
            val requeueForNight: Boolean get() = reason != Reason.DECODER_ERROR
        }
    }

    /** Why a tap was abandoned. Each one is a way the frame stream stopped being the source. */
    enum class Reason {
        /** Playback began somewhere other than the start, so the first frames never existed. */
        STARTED_MID_STREAM,

        /** The user scrubbed backwards. The encoder has already consumed those frames. */
        SEEKED_BACK,

        /** A jump forward — a scrub, or the player dropping frames to keep up. */
        SKIPPED_FORWARD,

        /** Playback ended, or the user left, before the last frame. */
        TRUNCATED,

        /** The user left the player while paused or playing. */
        LEFT_EARLY,

        /** Paused so long that holding an encoder session stopped being reasonable. */
        HELD_TOO_LONG,

        /**
         * Thermal headroom ran out.
         *
         * The night pass pauses and resumes for heat; this cannot, because the frames keep
         * coming whether the encoder is ready or not, and a paused encoder in a live tap is
         * a gap. So heat ends the tap and the night finishes the file.
         */
        THERMAL,

        DECODER_ERROR,
    }

    /** What the caller should do to the encoder. Everything else about it is the caller's. */
    enum class Effect {
        /** Open the encoder and the temp file. */
        START,

        /** Hand this frame to the encoder. */
        FEED,

        /** Playback paused; stop expecting frames. */
        HOLD,

        /** Playback resumed. */
        RESUME,

        /** Close the encoder; the temp file is complete and goes to verify. */
        FINISH,

        /** Close the encoder and delete the temp file. Always safe to repeat. */
        DISCARD,

        /** Nothing to do. */
        NONE,
    }

    sealed interface Event {
        data object Play : Event
        data object Pause : Event

        /** One decoded frame, with its presentation timestamp. */
        data class Frame(val ptsUs: Long) : Event

        /** The user moved the playhead. Reported before the frames that follow it. */
        data class SeekTo(val ptsUs: Long) : Event

        data object EndOfStream : Event

        /** The player was dismissed or the app backgrounded. */
        data object Leave : Event

        /** The caller's own hold timer fired (see [MAX_HELD_MS]). */
        data object HoldTimeout : Event

        data object Overheated : Event
        data object DecoderError : Event
    }

    var state: State = State.Armed
        private set

    /** How many frames have reached the encoder. */
    val frames: Int
        get() = when (val s = state) {
            is State.Recording -> s.frames
            is State.Held -> s.frames
            is State.Complete -> s.frames
            else -> 0
        }

    /** 0..1 for the progress bar, by playback position rather than by frame count. */
    val progress: Float
        get() {
            if (state is State.Complete) return 1f
            val duration = source.durationMs * 1_000L
            if (duration <= 0) return 0f
            return (lastPtsUs().toFloat() / duration).coerceIn(0f, 1f)
        }

    /**
     * Feeds one event in and says what to do with the encoder.
     *
     * Terminal states absorb everything: a `Leave` arriving after `EndOfStream` is the
     * ordinary way a player closes, and it must not discard a file that is already complete.
     */
    @Suppress("CyclomaticComplexMethod")
    fun on(event: Event): Effect {
        val current = state
        if (current is State.Complete || current is State.Abandoned) return Effect.NONE

        // The three that end a tap from any live state, before the per-state handling,
        // because "the user left" means the same thing whether we were playing or paused.
        when (event) {
            Event.Leave -> return abandon(Reason.LEFT_EARLY)
            Event.Overheated -> return abandon(Reason.THERMAL)
            Event.DecoderError -> return abandon(Reason.DECODER_ERROR)
            else -> Unit
        }

        return when (current) {
            is State.Armed -> when (event) {
                Event.Play -> {
                    state = State.Recording(frames = 0, lastPtsUs = -1)
                    Effect.START
                }
                // A frame before Play is the player's first-frame preview. It is not part of
                // a tap that has not started.
                else -> Effect.NONE
            }

            is State.Recording -> when (event) {
                is Event.Frame -> accept(current, event.ptsUs)
                is Event.SeekTo -> seek(current, event.ptsUs)
                Event.Pause -> {
                    state = State.Held(current.frames, current.lastPtsUs)
                    Effect.HOLD
                }
                Event.EndOfStream -> end(current.frames, current.lastPtsUs)
                else -> Effect.NONE
            }

            is State.Held -> when (event) {
                Event.Play -> {
                    state = State.Recording(current.frames, current.lastPtsUs)
                    Effect.RESUME
                }
                Event.HoldTimeout -> abandon(Reason.HELD_TOO_LONG)
                // Players re-render the current frame while paused. Re-delivering a frame we
                // already have is harmless and ignored; a *new* one means frames are being
                // decoded past an encoder that is not taking them, which is a gap.
                is Event.Frame ->
                    if (event.ptsUs > current.lastPtsUs) abandon(Reason.SKIPPED_FORWARD) else Effect.NONE
                is Event.SeekTo -> seek(current, event.ptsUs)
                Event.EndOfStream -> end(current.frames, current.lastPtsUs)
                else -> Effect.NONE
            }

            else -> Effect.NONE
        }
    }

    /**
     * One frame, checked for contiguity.
     *
     * The first frame has to be the first frame: a user who resumes a half-watched video
     * and taps Compress now would otherwise get an encode of the second half only, and it
     * would pass every later gate — the file opens, it is smaller, and VMAF sampled at the
     * start of *its own* timeline looks fine.
     */
    private fun accept(current: State.Recording, ptsUs: Long): Effect {
        if (current.frames == 0) {
            return if (ptsUs > gapToleranceUs()) {
                abandon(Reason.STARTED_MID_STREAM)
            } else {
                state = State.Recording(1, ptsUs)
                Effect.FEED
            }
        }

        val gap = ptsUs - current.lastPtsUs
        return when {
            gap == 0L -> Effect.NONE // the same frame delivered twice
            gap < 0L -> abandon(Reason.SEEKED_BACK)
            gap > gapToleranceUs() -> abandon(Reason.SKIPPED_FORWARD)
            else -> {
                state = State.Recording(current.frames + 1, ptsUs)
                Effect.FEED
            }
        }
    }

    private fun seek(current: State, toUs: Long): Effect {
        val last = when (current) {
            is State.Recording -> current.lastPtsUs
            is State.Held -> current.lastPtsUs
            else -> return Effect.NONE
        }
        val delta = toUs - last
        return when {
            // A "seek" to where we already are is what a player reports after a pause or a
            // resolution change. Nothing moved, so nothing is lost.
            delta in -gapToleranceUs()..gapToleranceUs() -> Effect.NONE
            delta < 0 -> abandon(Reason.SEEKED_BACK)
            else -> abandon(Reason.SKIPPED_FORWARD)
        }
    }

    /**
     * End of stream: complete only if the frames actually reached the end.
     *
     * `EndOfStream` on its own proves nothing — a player emits it when it stops, and it
     * stops for reasons other than finishing. The proof is the last timestamp.
     */
    private fun end(frames: Int, lastPtsUs: Long): Effect {
        val durationUs = source.durationMs * 1_000L
        val covered = frames > 0 && lastPtsUs >= durationUs - endToleranceUs()
        return if (covered) {
            state = State.Complete(frames)
            Effect.FINISH
        } else {
            abandon(Reason.TRUNCATED)
        }
    }

    private fun abandon(reason: Reason): Effect {
        state = State.Abandoned(reason)
        return Effect.DISCARD
    }

    private fun lastPtsUs(): Long = when (val s = state) {
        is State.Recording -> s.lastPtsUs.coerceAtLeast(0)
        is State.Held -> s.lastPtsUs.coerceAtLeast(0)
        else -> 0
    }

    /**
     * How big a jump between frames is still "the next frame".
     *
     * Sized from the clip's own frame rate, with a floor, because variable-frame-rate video
     * is common — a screen recording holds a still frame for as long as the screen holds
     * still — and a tolerance of one frame interval would abandon those constantly.
     *
     * The asymmetry is deliberate. Too tight, and a tap gives up needlessly and the file is
     * encoded tonight instead: one wasted encode. Too loose, and dropped frames reach the
     * encoder as a silently shortened video. Being wrong in the first direction is cheap,
     * so this errs there.
     */
    private fun gapToleranceUs(): Long {
        val fps = source.fps
        if (fps == null || fps <= 0.0) return UNKNOWN_FPS_GAP_US
        val interval = (1_000_000.0 / fps).toLong()
        return (interval * GAP_FRAMES).coerceAtLeast(MIN_GAP_US)
    }

    /**
     * How far short of the stated duration the last frame may fall.
     *
     * Container durations and the last frame's timestamp routinely disagree by a frame or
     * two — the duration includes the last frame's own display time, the timestamp does
     * not. This is the same 100 ms `Verifier` allows for the same reason.
     */
    private fun endToleranceUs(): Long = maxOf(gapToleranceUs(), END_TOLERANCE_US)

    companion object {
        /** Frame intervals of slack between consecutive frames. */
        const val GAP_FRAMES = 4L

        /** A floor for very high frame rates, where four intervals is under 20 ms. */
        const val MIN_GAP_US = 100_000L

        /** Tolerance when the container does not state a frame rate. */
        const val UNKNOWN_FPS_GAP_US = 500_000L

        /** Matches the duration tolerance in `Verifier`. */
        const val END_TOLERANCE_US = 100_000L

        /**
         * How long a pause may hold an encoder session open.
         *
         * Hardware encoder sessions are a scarce, device-wide resource; holding one because
         * a user paused to answer the door and then put the phone down would deny it to the
         * camera. Two minutes is long enough that no real pause hits it and short enough
         * that nothing is stuck. The caller runs the timer; this machine only says what to
         * do when it fires.
         */
        const val MAX_HELD_MS = 2 * 60 * 1000L
    }
}
