package app.trimgallery.engine.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.trimgallery.engine.NightConstraints
import app.trimgallery.engine.NightScheduler
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * When the OS is allowed to wake the night pass (BUILD.md § 11, ARCHITECTURE.md § 6).
 *
 * The constraints do most of the work of BUILD.md rule 6 before a line of our code runs:
 * WorkManager will not start the worker unless the phone is charging, idle and not short
 * of storage, and it *stops* the worker the moment any of those stops being true. The
 * guards inside the run are the second line — they catch the conditions the OS does not
 * know about (heat, the alarm, the caps) and the ones it re-checks too slowly.
 *
 * Periodic rather than one-shot so that a night the user misses — phone not charged, phone
 * in use — is simply picked up the next night without anything having to reschedule.
 */
class WorkManagerScheduler(private val context: Context) : NightScheduler {

    override fun schedule(constraints: NightConstraints) {
        val request = PeriodicWorkRequestBuilder<NightWorker>(
            repeatInterval = REPEAT_INTERVAL.inWholeMinutes,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = FLEX_INTERVAL.inWholeMinutes,
            flexTimeIntervalUnit = TimeUnit.MINUTES,
        )
            .setConstraints(constraints.toWorkManager())
            // ARCHITECTURE.md § 13: a window taken back by the OS is not a failure, and
            // the next attempt should be soon rather than at the next period boundary.
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF.inWholeMinutes, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NAME,
            // KEEP, not UPDATE: replacing the request resets the period, so a settings
            // change at 23:59 would otherwise push tonight's window past the morning.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    /**
     * Re-enqueues with new constraints, for when the user changes "start when full".
     *
     * Separate from [schedule] because it deliberately *does* reset the period, and that
     * is only acceptable when the user has just asked for something different.
     */
    fun reschedule(constraints: NightConstraints) {
        cancel()
        schedule(constraints)
    }

    private fun NightConstraints.toWorkManager(): Constraints = Constraints.Builder()
        .setRequiresCharging(requiresCharging)
        .setRequiresDeviceIdle(requiresIdle)
        .setRequiresStorageNotLow(requiresStorageNotLow)
        // BUILD.md rule 8: no network, ever. Stating it here as well as in the manifest
        // means the scheduler never waits on a condition the app has no business having.
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        // `requiresBatteryFull` has no WorkManager constraint — `setRequiresBatteryNotLow`
        // is a floor, not a ceiling — so it is enforced inside the run by the battery
        // guard, which pauses rather than stops.
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * What WorkManager currently holds for the night pass, for the diagnostics export.
     *
     * Deliberately **no times**. `Diagnostics` in `core/domain` bans absolute timestamps
     * from the export for a reason it states plainly: when the night pass ran says when
     * somebody sleeps. State and constraints answer "is it scheduled?" without answering
     * "when is this person unconscious?".
     */
    fun status(): NightPassStatus {
        val infos = runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(NAME).get()
        }.getOrNull().orEmpty()

        val info = infos.firstOrNull()
            ?: return NightPassStatus(scheduled = false, state = null, runAttempts = 0)

        return NightPassStatus(
            scheduled = true,
            state = info.state.name,
            runAttempts = info.runAttemptCount,
        )
    }

    private companion object {
        const val NAME = "trim-night-pass"
        const val TAG = "night"

        /**
         * Once a day, with a wide flex window.
         *
         * WorkManager decides when inside the flex; the charging-and-idle constraints mean
         * that in practice it lands overnight, which is what BUILD.md asks for without the
         * app having to guess the user's bedtime.
         */
        val REPEAT_INTERVAL = 24.hours
        val FLEX_INTERVAL = 8.hours
        val BACKOFF = 30.minutes
    }
}
