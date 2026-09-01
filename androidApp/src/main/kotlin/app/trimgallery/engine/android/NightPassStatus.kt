package app.trimgallery.engine.android

import app.trimgallery.engine.NightConstraints

/**
 * Whether the night pass is actually scheduled, in a form safe to export.
 *
 * This exists because "the night pass is wired up" was a claim nobody could check from a
 * phone. `NightScheduler.schedule` was written in milestone 5, bound in Koin, and **never
 * called by anything** — so no device has ever had the work enqueued, which is the real
 * reason nothing has ever been optimised. A field report cannot see a WorkManager row, so
 * the app has to say.
 *
 * No timestamps, by the same rule `Diagnostics` states for the metrics export: when the
 * night pass last ran says when its owner sleeps. State and attempt count carry the fact
 * that matters — is it enqueued — and none of that.
 */
data class NightPassStatus(
    val scheduled: Boolean,
    /** WorkManager's own name for the state: ENQUEUED, RUNNING, BLOCKED and so on. */
    val state: String?,
    val runAttempts: Int,
) {

    fun lines(constraints: NightConstraints, grantedFolders: Int): String = buildString {
        appendLine("--- night pass ---")
        appendLine("scheduled: ${if (scheduled) "yes" else "no"}")
        if (state != null) appendLine("state: $state")
        appendLine("run attempts: $runAttempts")
        appendLine("granted folders: $grantedFolders")
        appendLine(
            "constraints: " + listOfNotNull(
                "charging".takeIf { constraints.requiresCharging },
                "idle".takeIf { constraints.requiresIdle },
                "storage-not-low".takeIf { constraints.requiresStorageNotLow },
                "battery-full".takeIf { constraints.requiresBatteryFull },
            ).joinToString(", ").ifEmpty { "none" },
        )
        if (!scheduled && grantedFolders > 0) {
            // The exact fault this was built to catch, named in the export rather than
            // left for somebody to infer from an absence.
            appendLine("WARNING: folders are granted but no work is enqueued.")
        }
    }
}
