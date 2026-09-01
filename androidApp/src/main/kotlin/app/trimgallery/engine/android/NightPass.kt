package app.trimgallery.engine.android

import app.trimgallery.engine.NightConstraints
import app.trimgallery.engine.NightScheduler

/**
 * Enqueues the night pass, and is the only thing that does.
 *
 * `NightScheduler.schedule` has existed since milestone 5 — periodic request, constraints,
 * linear backoff, unique work under KEEP — and until now **nothing called it**. The
 * pipeline, the guards, the encoder and the safe replace were all built and tested behind
 * a door nobody opened, which is why a phone with the app installed and a folder granted
 * has never optimised anything.
 *
 * Called from two places, and both are needed:
 *
 * - when a folder is granted, because that is the moment the app first has work to do;
 * - on every app start where a grant already exists, because a periodic work request does
 *   not survive an app being reinstalled, and the user is not going to re-grant a folder
 *   to fix something they cannot see.
 *
 * `enqueueUniquePeriodicWork(KEEP)` makes both safe to call as often as they like: an
 * existing schedule is left exactly as it is, period intact.
 */
class NightPass(private val scheduler: NightScheduler, private val folders: GrantedFolders) {

    /**
     * Schedules if any folder is granted; cancels if none is.
     *
     * The cancel half matters as much as the schedule half: a user who revokes their last
     * grant in system Settings should not leave a job waking the phone every night to
     * discover it has nothing to read.
     */
    fun sync(constraints: NightConstraints = NightConstraints()): Boolean {
        val granted = folders.grants().isNotEmpty()
        if (granted) scheduler.schedule(constraints) else scheduler.cancel()
        return granted
    }
}
