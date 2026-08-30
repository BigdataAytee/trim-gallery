package app.trimgallery.core.domain.lock

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The locked folder's gate (BUILD.md § 9: "Locked folder (biometric)").
 *
 * A state machine rather than a boolean, because the interesting cases are the ones a
 * boolean loses: what happens when authentication fails, and when the folder re-locks.
 * Pure and unit tested; the platform supplies only `BiometricPrompt` / `LocalAuthentication`
 * results.
 */
object LockedFolderGate {

    sealed interface State {
        /** The default, and where every path eventually returns. */
        data object Locked : State

        /** A prompt is on screen. */
        data object Authenticating : State

        /** Open until [until]. */
        data class Unlocked(val until: Instant) : State

        /** The last attempt failed; [message] is shown above the retry. */
        data class Failed(val message: String) : State
    }

    sealed interface Event {
        data object Open : Event
        data object Succeeded : Event
        data class Failed(val message: String) : Event
        data object Cancelled : Event

        /** The app went to the background. */
        data object Backgrounded : Event
        data object Tick : Event
    }

    /**
     * How long an unlock lasts.
     *
     * Short on purpose. The folder exists so that someone holding the unlocked phone
     * cannot see what is in it, and a long session would defeat that the first time the
     * user handed the phone over to show someone a photo.
     */
    val SESSION: Duration = 120.seconds

    /**
     * @param now used for the expiry, so tests need no clock and the caller can drive it
     *   from whatever time source the platform prefers.
     */
    fun reduce(state: State, event: Event, now: Instant): State = when (event) {
        // Backgrounding always re-locks, from any state. This is the whole point of the
        // feature: putting the phone down must close the folder.
        Event.Backgrounded -> State.Locked

        Event.Open -> when (state) {
            is State.Unlocked -> if (isExpired(state, now)) State.Authenticating else state
            State.Authenticating -> state
            else -> State.Authenticating
        }

        Event.Succeeded -> State.Unlocked(now + SESSION)

        is Event.Failed -> State.Failed(event.message)

        // A cancelled prompt is not a failure and must not show an error.
        Event.Cancelled -> State.Locked

        Event.Tick -> if (state is State.Unlocked && isExpired(state, now)) State.Locked else state
    }

    fun isUnlocked(state: State, now: Instant): Boolean = state is State.Unlocked && !isExpired(state, now)

    private fun isExpired(state: State.Unlocked, now: Instant): Boolean = state.until <= now
}
