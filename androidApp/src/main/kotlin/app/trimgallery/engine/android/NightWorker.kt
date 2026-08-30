package app.trimgallery.engine.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.trimgallery.R
import app.trimgallery.core.model.StopReason
import app.trimgallery.core.pipeline.night.NightBudget
import app.trimgallery.core.pipeline.night.NightFacts
import app.trimgallery.core.pipeline.night.NightRun
import app.trimgallery.core.pipeline.night.RunSessionTracker
import app.trimgallery.engine.UndoStore
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * The night, as Android runs it (BUILD.md § 13.5).
 *
 * A **long-running** worker, which on Android means a foreground service with a
 * notification. That is not a choice: WorkManager stops an ordinary worker after ten
 * minutes, and BUILD.md § 6 budgets sixty. USER_JOURNEY.md § 3 says the night has no UI,
 * so the notification is posted on a minimum-importance channel — no sound, no heads-up,
 * no badge; it appears only if the user pulls the shade down at 3am, where seeing "Trim is
 * working" is better than wondering what is using the battery. Recorded in PROJECT.md.
 *
 * Everything the worker actually decides lives in `NightRun` and `GuardChain`, which are
 * shared and unit tested. This class supplies Android's answers and Android's plumbing.
 */
class NightWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {

    override suspend fun doWork(): Result {
        val facts: NightFacts = get()
        val queue: NightRun.Queue = get()
        val step: NightRun.Step = get()
        val checkpoint: NightRun.Checkpoint = get()
        val onInterrupted: NightRun.OnInterrupted = get()
        val undo: UndoStore = get()
        val sessions: RunSessionIds = get()

        setForeground(foregroundInfo())

        val startedAt = System.currentTimeMillis()
        val budget = NightBudget(facts.settings().nightlyCapMinutes)

        // Built here, not injected: the guards need this run's own worked-time, and a
        // singleton would report the previous night's.
        val guards = AndroidGuards(
            context = applicationContext,
            facts = facts,
            foreground = { ForegroundWatcher.isForeground },
            workedMs = { budget.workedMs(System.currentTimeMillis()) },
        )

        val tracker = RunSessionTracker(sessions.newId(), startedAt, budget)
        val night = NightRun(guards, tracker, budget, System::currentTimeMillis)

        return try {
            val session = night.run(queue, step, checkpoint, onInterrupted)

            // ARCHITECTURE.md § 7 ends the pass here: originals whose window has closed
            // are deleted for good. After the run, never during it — a sweep racing a
            // replace could remove the very original a rollback was about to need.
            undo.sweep(System.currentTimeMillis())

            when (session.stopReason) {
                // Nothing left to do, or the night is genuinely over. Wait for the next
                // scheduled window rather than asking to be run again.
                StopReason.COMPLETE, StopReason.CAP, StopReason.STOP_BY, StopReason.CAP_FREE_TIER ->
                    Result.success()

                // The condition can clear on its own. Let WorkManager back off and retry
                // rather than losing the rest of the night to a warm ten minutes.
                else -> Result.retry()
            }
        } catch (e: CancellationException) {
            // The OS took the window back. Not a failure: the queue and the checkpointed
            // session survive, and the next window picks up where this one stopped.
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Broad on purpose: a night that dies must still hand WorkManager a verdict, and
            // an unexpected exception here is exactly the case a narrow catch would miss.
            //
            // Logged rather than discarded. `run_session` has no column for a failure — the
            // row records how the night *stopped*, not that it fell over — so until it does
            // (PROJECT.md § Open questions) this line is the only trace of why a night
            // produced nothing.
            Log.w(TAG, "night pass failed; asking WorkManager to retry", e)
            Result.retry()
        }
    }

    /**
     * The one notification the night shows.
     *
     * `mediaProcessing` where the platform has it (API 35+): it describes what this
     * actually is, and Android 15 applies the data-sync six-hour daily budget to
     * `dataSync` in a way that would eventually cut nights short.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification: Notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.night_notification_title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            // MIN, not LOW: the user is asleep. This exists to answer "what is using my
            // phone at 3am", not to tell them anything.
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.night_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    /** Mints `run_session` ids. UUIDv7, per SCHEMA.md. */
    fun interface RunSessionIds {
        fun newId(): String
    }

    companion object {
        private const val TAG = "NightWorker"
        const val CHANNEL_ID = "night-pass"
        const val NOTIFICATION_ID = 1001
    }
}
