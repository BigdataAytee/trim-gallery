package app.trimgallery.engine.android

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.trimgallery.core.domain.compress.PlayToCompress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Play-to-compress on Android: ExoPlayer's callbacks translated into the decisions
 * `PlayToCompress` makes (BUILD.md § 9, ARCHITECTURE.md § 5).
 *
 * The split here is deliberate and worth stating, because the obvious version of this class
 * would put the rules in it. *When to give up on a tap* is a product decision — it is the
 * difference between a user getting a smaller video and a user getting a video with a hole
 * in it — so it lives in shared code with tests, and iOS will get exactly the same
 * behaviour from `AVPlayerItemVideoOutput` callbacks. This class contains no such decision.
 * It maps events in and effects out, and that is all.
 *
 * ### What this class is not
 *
 * It does not contain the GL tee. Feeding the decoder's frames to an encoder's input
 * surface while they also reach the screen is a `GlEffect` on
 * `ExoPlayer.setVideoEffects`, and it has to be built and measured on a device: whether the
 * tee costs a dropped frame at 4K60 is not a thing that can be reasoned out. That work is
 * behind [EncoderSink], which is the seam. Until an implementation of it exists, this class
 * is the finished half — and the half that decides whether a file is safe to keep.
 *
 * ### Ordering
 *
 * Every method must be called from the player's application thread, which is where
 * ExoPlayer delivers its own callbacks. [onFrame] arrives from the video-effect pipeline
 * instead, so the implementation of [EncoderSink] is responsible for getting it there; the
 * state machine is not thread-safe and does not pretend to be.
 */
@UnstableApi
class PlayToCompressTap(
    source: PlayToCompress.Source,
    private val sink: EncoderSink,
    private val scope: CoroutineScope,
    /** Called once, when the tap reaches a terminal state. */
    private val onFinished: (PlayToCompress.State) -> Unit,
) : Player.Listener {

    /**
     * The encoder, as this class needs it.
     *
     * Narrow on purpose. Everything about surfaces, formats and muxing is on the other
     * side; what is required here is that [discard] leaves nothing behind and is safe to
     * call twice, because that is the guarantee the "nothing partial is ever committed"
     * rule rests on.
     */
    interface EncoderSink {
        fun start()

        /** Encode the frame at this presentation time. */
        fun feed(presentationTimeUs: Long)

        fun hold()
        fun resume()

        /** Close the file. It is complete and goes to `VerifyPass` like any other encode. */
        fun finish()

        /** Close the encoder and delete the partial file. Safe to call more than once. */
        fun discard()
    }

    private val tap = PlayToCompress(source)
    private var holdTimer: Job? = null
    private var reported = false

    /** For the progress bar in the player's chrome. */
    val progress: Float get() = tap.progress

    val state: PlayToCompress.State get() = tap.state

    /** One decoded frame reached the effect pipeline. */
    fun onFrame(presentationTimeUs: Long) = apply(PlayToCompress.Event.Frame(presentationTimeUs))

    /** The user dismissed the player, or the app went to the background. */
    fun leave() = apply(PlayToCompress.Event.Leave)

    /**
     * Thermal headroom ran out.
     *
     * Polled by `AndroidGuards` for the night pass; wired here so a tap does not keep an
     * encoder busy on a phone that is already throttling. The night pass will finish the
     * file when the phone is cool and charging, which is where it belonged anyway.
     */
    fun overheated() = apply(PlayToCompress.Event.Overheated)

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        apply(if (isPlaying) PlayToCompress.Event.Play else PlayToCompress.Event.Pause)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) apply(PlayToCompress.Event.EndOfStream)
    }

    /**
     * A seek, or any other jump in the timeline.
     *
     * `DISCONTINUITY_REASON_AUTO_TRANSITION` is excluded because it means the playlist moved
     * to another item, which is a different file and not a seek within this one — the tap
     * ends there through [leave] instead, with the reason the user would recognise.
     */
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            apply(PlayToCompress.Event.Leave)
        } else {
            apply(PlayToCompress.Event.SeekTo(newPosition.positionMs * 1_000L))
        }
    }

    override fun onPlayerError(error: PlaybackException) = apply(PlayToCompress.Event.DecoderError)

    private fun apply(event: PlayToCompress.Event) {
        when (tap.on(event)) {
            PlayToCompress.Effect.START -> sink.start()
            // Only a Frame can produce FEED; the check is here so a future event that could
            // would fail to compile rather than feed the encoder a timestamp it invented.
            PlayToCompress.Effect.FEED -> if (event is PlayToCompress.Event.Frame) sink.feed(event.ptsUs)
            PlayToCompress.Effect.HOLD -> { sink.hold(); startHoldTimer() }
            PlayToCompress.Effect.RESUME -> { cancelHoldTimer(); sink.resume() }
            PlayToCompress.Effect.FINISH -> { cancelHoldTimer(); sink.finish() }
            PlayToCompress.Effect.DISCARD -> { cancelHoldTimer(); sink.discard() }
            PlayToCompress.Effect.NONE -> Unit
        }
        report()
    }

    /**
     * Gives the encoder back if a pause turns into the user putting the phone down.
     *
     * Hardware encoder sessions are device-wide and scarce; holding one through an
     * indefinite pause would deny it to the camera. The timeout itself is the state
     * machine's constant, so both platforms release at the same point.
     */
    private fun startHoldTimer() {
        cancelHoldTimer()
        holdTimer = scope.launch {
            delay(PlayToCompress.MAX_HELD_MS)
            apply(PlayToCompress.Event.HoldTimeout)
        }
    }

    private fun cancelHoldTimer() {
        holdTimer?.cancel()
        holdTimer = null
    }

    private fun report() {
        if (reported) return
        val current = tap.state
        if (current is PlayToCompress.State.Complete || current is PlayToCompress.State.Abandoned) {
            reported = true
            onFinished(current)
        }
    }
}
