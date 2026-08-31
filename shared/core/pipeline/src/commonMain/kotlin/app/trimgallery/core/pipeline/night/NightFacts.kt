package app.trimgallery.core.pipeline.night

import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.Settings

/**
 * The numbers the guards need that only the database knows.
 *
 * One interface rather than five constructor lambdas, so that the platform `Guards` reads
 * a coherent set: the queue's largest file and the next file's estimated saving describe
 * the *same* queue, and fetching them through separate closures invites two callers to
 * disagree about which queue they meant.
 *
 * Suspending because every one of these is a query. ARCHITECTURE.md § 8 puts them on the
 * IO dispatcher; the guard check is already suspending, so nothing here needs to block.
 */
interface NightFacts {

    /** The shared DataStore (ARCHITECTURE.md § 12). */
    suspend fun settings(): Settings

    /** Free or Pro, for the monthly cap (MONETIZATION.md § Phase 1). */
    suspend fun tier(): Tier

    /**
     * The largest file still queued.
     *
     * Sets how much room the pass needs: BUILD.md § 6 wants twice this free, because the
     * original and its replacement both exist between the encode and the commit.
     */
    suspend fun largestPendingBytes(): Long

    /** Summed from `run_session`, not counted, so the cap cannot drift from what happened. */
    suspend fun bytesFreedThisMonth(): Long

    /** What the next file is expected to save, checked against the free tier's allowance. */
    suspend fun nextSavingBytes(): Long
}
