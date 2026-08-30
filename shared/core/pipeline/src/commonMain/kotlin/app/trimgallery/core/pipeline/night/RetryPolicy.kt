package app.trimgallery.core.pipeline.night

/**
 * What to do when the encoder is taken away.
 *
 * ARCHITECTURE.md § 13: *"Codec reclaimed / session interrupted → Job.PAUSED, retry
 * 5/15/60 s."* The delays are fixed rather than exponential-with-jitter because the thing
 * being waited for is a person: the codec was reclaimed because something in the
 * foreground wanted it — a camera, a video call — and those last seconds to minutes.
 *
 * Three attempts and then the file is left for another night. A codec that is still gone
 * after 80 seconds is not coming back soon, and the queue has other work.
 */
object RetryPolicy {

    val BACKOFF_MS = listOf(5_000L, 15_000L, 60_000L)

    val maxAttempts: Int get() = BACKOFF_MS.size

    /**
     * How long to wait before attempt [attempt] (0-based), or null when there are no
     * attempts left and the item should go back in the queue.
     */
    fun delayFor(attempt: Int): Long? = BACKOFF_MS.getOrNull(attempt)

    fun exhausted(attempt: Int): Boolean = attempt >= maxAttempts
}
