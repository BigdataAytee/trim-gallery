package app.trimgallery.di

import androidx.media3.common.util.UnstableApi
import app.trimgallery.core.data.AndroidDatabase
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.Uuid7
import app.trimgallery.core.pipeline.night.NightFacts
import app.trimgallery.core.pipeline.night.NightRun
import app.trimgallery.core.pipeline.replace.OriginalLocator
import app.trimgallery.core.pipeline.replace.UndoJournal
import app.trimgallery.engine.CodecFactory
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.MetadataCopier
import app.trimgallery.engine.NightScheduler
import app.trimgallery.engine.OutputProbe
import app.trimgallery.engine.QualityScorer
import app.trimgallery.engine.Replacer
import app.trimgallery.engine.UndoStore
import app.trimgallery.engine.android.MediaCodecFactory
import app.trimgallery.engine.android.MetadataCopierAndroid
import app.trimgallery.engine.android.NativeQualityScorer
import app.trimgallery.engine.android.NightWorker
import app.trimgallery.engine.android.OutputProbeAndroid
import app.trimgallery.engine.android.SafStorage
import app.trimgallery.engine.android.SafeReplacerAndroid
import app.trimgallery.engine.android.UndoBinAndroid
import app.trimgallery.engine.android.WorkManagerScheduler
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Binds the Android implementations of the `shared/engine-api` interfaces
 * (ARCHITECTURE.md § 3, § 5).
 *
 * Shared code depends on the interfaces only; this module is the single place the Android
 * engines are named. The iOS equivalent will bind `VideoToolboxFactory`, `SafeReplacerIos`
 * and the rest against the same interfaces.
 */
@UnstableApi
val androidEngineModule = module {
    single<CodecFactory> { MediaCodecFactory(androidContext()) }

    // Milestone 2. XPSNR and VMAF over the native C ABI; the pipeline never sees JNI.
    single<QualityScorer> { NativeQualityScorer() }

    // --- Milestone 4: verify, safe replace, undo -----------------------------
    //
    // Ids are minted from one generator per process so that the UUIDv7 counter stays
    // monotonic within a millisecond (SCHEMA.md; see core.model.Uuid7, which documents why
    // it must be confined to a single thread — ARCHITECTURE.md § 8 puts every database
    // write on the IO dispatcher).
    single { Uuid7() }

    single<LibraryStorage> { SafStorage(androidContext(), newId = { get<Uuid7>().next(System.currentTimeMillis()) }) }
    single<OutputProbe> { OutputProbeAndroid() }
    single<MetadataCopier> { MetadataCopierAndroid(androidContext()) }

    single<UndoStore> {
        UndoBinAndroid(
            context = androidContext(),
            journal = get(),
            originals = get(),
            newId = { get<Uuid7>().next(System.currentTimeMillis()) },
        )
    }

    single<Replacer> {
        SafeReplacerAndroid(
            context = androidContext(),
            storage = get(),
            metadata = get(),
            undo = get(),
            journal = get<UndoJournal>(),
        )
    }

    // --- Milestone 5: scheduling --------------------------------------------
    //
    // `Guards` is deliberately absent here: it needs the run's own worked-time, so
    // NightWorker builds it per run. Everything a guard decides lives in GuardChain,
    // which is platform-free and unit tested (ARCHITECTURE.md § 15, "Guards composition").
    single<NightScheduler> { WorkManagerScheduler(androidContext()) }

    single { NightWorker.RunSessionIds { get<Uuid7>().next(System.currentTimeMillis()) } }

    // --- The database ---------------------------------------------------------
    single { AndroidDatabase.create(androidContext()) }

    /*
     * One repository implementing several small ports — UndoJournal, OriginalLocator,
     * NightFacts and the night queue — because they all read and write the same tables
     * inside the same transactions. Splitting them would let two of them disagree about
     * what the queue currently is.
     */
    single {
        TrimRepository(
            db = get(),
            io = Dispatchers.IO,
            newId = { get<Uuid7>().next(System.currentTimeMillis()) },
            nowMs = System::currentTimeMillis,
            // DataStore-backed settings land with the Settings screen (milestone 10);
            // until then the pass runs on the documented defaults (ARCHITECTURE.md § 12).
            readSettings = { Settings() },
            readTier = { Tier.FREE },
            monthStartMs = { startOfCurrentMonthMs() },
        )
    }

    single<UndoJournal> { get<TrimRepository>() }
    single<OriginalLocator> { get<TrimRepository>() }
    single<NightFacts> { get<TrimRepository>() }
    single<NightRun.Queue> { get<TrimRepository>() }
    single<NightRun.Checkpoint> { get<TrimRepository>() }
    single<NightRun.OnInterrupted> { get<TrimRepository>() }

    // NightRun.Step is VideoOptimiseStep, which chains triage → search → encode → verify →
    // replace. Triage is milestone 6; until it can decide what belongs in the queue at
    // all, there is nothing honest to bind here.
    //
    // The indexer lands with milestone 9.
}

/**
 * Midnight on the first of the current month, in the device's own zone.
 *
 * MONETIZATION.md caps GB freed per calendar month, and "calendar" means the user's
 * calendar: a UTC month boundary would reset someone in Auckland thirteen hours early.
 */
private fun startOfCurrentMonthMs(): Long {
    val zone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(zone).date
    return LocalDate(today.year, today.month, 1)
        .atStartOfDayIn(zone)
        .toEpochMilliseconds()
}
