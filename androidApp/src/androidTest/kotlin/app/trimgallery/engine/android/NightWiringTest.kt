package app.trimgallery.engine.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trimgallery.core.pipeline.night.NightFacts
import app.trimgallery.core.pipeline.night.NightRun
import app.trimgallery.core.pipeline.video.VideoOptimiseStep
import app.trimgallery.engine.UndoStore
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * That the night pass can build the thing it runs, asked of the real graph on a device.
 *
 * `NightWorker.doWork` resolves seven definitions before it does anything, and until now
 * one of them — `NightRun.Step` — had no binding at all. Nothing failed, because nothing
 * ever ran: the failure was scheduled for the first charging night, in a worker, with no
 * screen, at 3am. It would have surfaced as "the app never optimises anything" rather than
 * as a crash anyone could see.
 *
 * That is what makes a wiring test worth its weight here specifically. A missing Koin
 * definition is not caught by compilation and not caught by any unit test — every one of
 * those constructs its subject directly, which is exactly the step this skips. The only
 * thing that catches it is asking the assembled graph for what the worker asks for.
 *
 * These tests deliberately resolve **the same list `NightWorker` resolves, in the same
 * order**. When a definition goes missing again, the failure names it here, in CI, rather
 * than on a stranger's phone overnight.
 */
@RunWith(AndroidJUnit4::class)
class NightWiringTest {

    /**
     * The graph the running `Application` built.
     *
     * Note that another instrumented test may have `declare`d fakes into this graph before
     * this one runs — Koin declarations are process-wide and there is no undeclare. That is
     * harmless to what is being asserted: the question here is whether every definition
     * *resolves*, not which implementation answers.
     */
    private val koin get() = GlobalContext.get()

    @Test
    fun theNightPassCanResolveEverythingItRuns() {
        // Exactly NightWorker.doWork's own resolutions. `NightRun.Step` is the one that was
        // missing; the rest are here so that this test keeps meaning "the night can start"
        // rather than "one binding exists".
        assertNotNull(koin.get<NightFacts>())
        assertNotNull(koin.get<NightRun.Queue>())
        assertNotNull(koin.get<NightRun.Step>())
        assertNotNull(koin.get<NightRun.Checkpoint>())
        assertNotNull(koin.get<NightRun.OnInterrupted>())
        assertNotNull(koin.get<UndoStore>())
        assertNotNull(koin.get<NightWorker.RunSessionIds>())
    }

    @Test
    fun theWholeOptimiseChainResolves() {
        // One `get`, seven constructor arguments, and each of those has its own. Resolving
        // the step is the cheapest way to assert that the entire chain ARCHITECTURE.md § 7
        // describes — storage, codecs, containers, search, verify, replace, facts — is
        // present and buildable on this device. Koin names the missing type if one is not.
        assertNotNull(koin.get<VideoOptimiseStep>())
    }
}
