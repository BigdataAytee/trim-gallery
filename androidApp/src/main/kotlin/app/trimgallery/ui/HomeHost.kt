package app.trimgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.pipeline.LibraryDiff
import app.trimgallery.core.ui.format.MediaFormatting
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimShape
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.LibraryStorage
import app.trimgallery.engine.SettingsStore
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.NightPass
import app.trimgallery.engine.android.NightPassStatus
import app.trimgallery.engine.android.StartupGuard
import app.trimgallery.engine.android.WorkManagerScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * What the launcher icon opens now the gallery is gone: how much is in the granted folders,
 * and the way to grant another.
 *
 * **This is deliberately not the Home screen BUILD.md § 9 describes.** "Find big files",
 * the freed total, the next scheduled run and the on/off toggle arrive with the real Home,
 * and this file becomes it. What it is today is the *wiring* the gallery host was carrying
 * — the fast start from the database, the folder walk, the library diff, the startup guard
 * and the recovery path — kept intact while the screen above it changes.
 *
 * Separating those two was the point of doing this in its own change. All of it is work the
 * app begins by itself, at launch, over a persisted grant: exactly the code path that
 * produced a crash loop once already. Deleting the gallery and rewriting this wiring in one
 * step would have meant a screen nobody had seen and a startup nobody had tested, with any
 * failure ambiguous between the two. The wiring below is byte-for-byte what shipped, minus
 * the grid it used to feed.
 */
@Composable
fun HomeHost(
    modifier: Modifier = Modifier,
    onStartupFailure: () -> Unit = {},
    onFolders: () -> Unit = {},
    chrome: @Composable BoxScope.() -> Unit = {},
    storage: LibraryStorage = koinInject(),
    repository: TrimRepository = koinInject(),
    folders: GrantedFolders = koinInject(),
    nightPass: NightPass = koinInject(),
    guard: StartupGuard = koinInject(),
    crashes: CrashReports = koinInject(),
    scheduler: WorkManagerScheduler = koinInject(),
    store: SettingsStore = koinInject(),
) {
    var grants by remember { mutableStateOf(folders.grants()) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var scanning by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Distinguishes "the database is still opening" from "there is genuinely nothing here",
    // so the first frame is not an empty-state message that is about to be wrong.
    var loaded by remember { mutableStateOf(false) }
    var freedTotal by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    // What the night pass is doing, and whether the user has it switched on at all. Both
    // are read once and re-read when the switch is used: neither changes while this screen
    // is open unless the user changes it here.
    var enabled by remember { mutableStateOf(true) }
    var nextRun by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { enabled = store.read().nightPassEnabled }
    LaunchedEffect(enabled, grants) { nextRun = nextRunLine(scheduler.status()) }

    val picker = rememberFolderPicker(
        folders = folders,
        nightPass = nightPass,
        nightPassEnabled = { enabled },
    ) { grants = it }

    // What is already known, before any folder is walked. This is the whole of the fast
    // start: the rows were written the last time the app ran, and reading them is one
    // indexed query rather than a walk of every granted tree.
    LaunchedEffect(Unit) {
        items = repository.gallery()
        // Summed from the recorded runs rather than held as a counter: see HomeBody.
        freedTotal = repository.runSessions().sumOf { it.bytesFreed }
        loaded = true
    }

    // Then the walk, in the background, with the result diffed in. Nothing here blocks a
    // frame.
    LaunchedEffect(grants) {
        if (grants.isEmpty()) {
            items = emptyList()
            return@LaunchedEffect
        }
        scanning = true
        failure = null

        // Everything from here to `guard.complete()` is work the app starts by itself. A
        // crash inside it is one the user cannot escape by not doing it, so it is bracketed:
        // the mark survives a process death this code cannot catch, and the catch below
        // handles the ones it can.
        guard.begin()
        try {
            // Before anything reads or writes media: the rows the scan's inserts point at.
            // `media_item.folder_grant_id` references `folder_grant(id)` and foreign keys are on,
            // so a scan that ran before this did threw on its first insert — and threw again on
            // every launch, because the grant it was scanning had already been persisted.
            repository.recordGrants(grants)

            val scanned = mutableListOf<MediaItem>()
            storage.scan(grants)
                .catch { failure = it.message ?: it::class.simpleName }
                .collect { scanned += it }

            if (failure == null) {
                // Off the main thread: the diff is a hash join over the whole library, and
                // `applyScan` writes every change in one transaction.
                val changes = withContext(Dispatchers.Default) {
                    LibraryDiff.diff(
                        stored = repository.stored(grants),
                        scanned = scanned,
                        scannedGrants = grants.map { it.id }.toSet(),
                    )
                }
                if (!changes.isEmpty) {
                    repository.applyScan(changes)
                }
                // Re-read rather than patching the list in memory: the query is the one place
                // that knows the sort, the hidden-item rule and the date fallback.
                items = repository.gallery()
            }
            scanning = false
            guard.complete()
        } catch (cancelled: CancellationException) {
            // Leaving the screen is not a failure. The mark has to come off, or the
            // next launch would open into a recovery screen for a scan nobody was
            // waiting on any more.
            guard.complete()
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failed: Throwable) {
            // Deliberately everything, and deliberately **not** clearing the mark.
            //
            // This is the crash the guard exists for: it happened inside work the app
            // began on its own, over an input — a persisted folder grant — that will
            // still be there next launch. Letting it propagate kills the process, and
            // the next launch does the same thing, which is the loop. Caught, it
            // becomes a screen the user can act on.
            //
            // Written to the crash store on the way past, because the recovery screen shows
            // what happened by reading that store — and a caught exception never reaches the
            // uncaught handler that normally fills it. Without this line the screen would
            // open on "No crashes recorded" for the very failure that opened it.
            crashes.record(failed)
            scanning = false
            onStartupFailure()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(TrimTheme.colors.page)
            .testTag(HomeTestTags.SCREEN),
    ) {
        when {
            grants.isEmpty() && loaded -> NoFolderYet { picker.choose() }
            else -> HomeBody(
                items = items,
                freedTotal = freedTotal,
                scanning = scanning,
                failure = failure,
                enabled = enabled,
                nextRun = nextRun,
                onToggle = {
                    val next = !enabled
                    enabled = next
                    scope.launch {
                        store.update { it.copy(nightPassEnabled = next) }
                        nightPass.sync(enabled = next)
                        nextRun = nextRunLine(scheduler.status())
                    }
                },
                onFolders = onFolders,
                onChoose = { picker.choose() },
            )
        }
        chrome()
    }
}

/**
 * Home, once there is a folder to talk about.
 *
 * Three facts and one switch. The freed total is summed from recorded runs rather than
 * kept as a counter, for the reason the Space screen gives: a counter drifts the first time
 * a run is killed mid-write, and a number the user cannot trust is worse than no number.
 *
 * There is no "Find big files" here yet. It is Home's primary action in BUILD.md § 9 and it
 * arrives with the screen it opens — a button that goes nowhere is the thing this project
 * has already written down as unforgivable.
 */
@Composable
private fun HomeBody(
    items: List<MediaItem>,
    freedTotal: Long,
    scanning: Boolean,
    failure: String?,
    enabled: Boolean,
    nextRun: String?,
    onToggle: () -> Unit,
    onFolders: () -> Unit,
    onChoose: () -> Unit,
) {
    val colors = TrimTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
        modifier = Modifier.fillMaxSize().padding(TrimSpacing.INSET_DP.dp),
    ) {
        BasicText(
            text = if (freedTotal > 0) "Freed ${MediaFormatting.bytes(freedTotal)}" else "Nothing freed yet",
            style = TrimTheme.typography.title.copy(color = colors.text),
            modifier = Modifier.testTag(HomeTestTags.FREED),
        )

        BasicText(
            text = when {
                items.isEmpty() && scanning -> "Looking through your folders…"
                failure != null -> "That folder could not be read: $failure"
                items.isEmpty() -> "Nothing here that this app can work with yet."
                else -> "${items.size} files, ${MediaFormatting.bytes(items.sumOf { it.size })} in your folders"
            },
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )

        BasicText(
            text = when {
                !enabled -> "Overnight trimming is off."
                nextRun != null -> nextRun
                else -> "Nothing scheduled yet."
            },
            style = TrimTheme.typography.body.copy(color = colors.muted),
            modifier = Modifier.testTag(HomeTestTags.NEXT_RUN),
        )

        BasicText(
            text = if (enabled) "Turn overnight trimming off" else "Turn overnight trimming on",
            style = TrimTheme.typography.label.copy(color = colors.accent),
            modifier = Modifier.pressScale(onToggle).testTag(HomeTestTags.TOGGLE),
        )

        BasicText(
            text = "Folders",
            style = TrimTheme.typography.label.copy(color = colors.accent),
            modifier = Modifier.pressScale(onFolders).testTag(HomeTestTags.FOLDERS),
        )

        if (items.isEmpty() && !scanning) {
            BasicText(
                text = "Choose another folder",
                style = TrimTheme.typography.label.copy(color = colors.accent),
                modifier = Modifier.pressScale(onChoose),
            )
        }
    }
}

/**
 * What the schedule can honestly be said to be.
 *
 * WorkManager gives a window rather than a time, and where in it the run lands is the OS's
 * business. So this says what is true — that it is scheduled and under what conditions —
 * rather than a clock time the app would be inventing. Same wording as the Space screen,
 * because the same fact should not be phrased two ways in one app.
 */
private fun nextRunLine(status: NightPassStatus): String? = when {
    !status.scheduled -> null
    status.runAttempts > 0 ->
        "Scheduled, but the last run did not finish. Export diagnostics from Settings has the detail."
    else -> "Scheduled for tonight, once the phone is charging and idle."
}

@Composable
private fun NoFolderYet(onChoose: () -> Unit) {
    val colors = TrimTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(colors.page),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
            modifier = Modifier.padding(TrimSpacing.INSET_DP.dp),
        ) {
            BasicText(
                text = "Trim",
                style = TrimTheme.typography.title.copy(color = colors.text, textAlign = TextAlign.Center),
            )
            BasicText(
                text = "Choose the folder your videos and photos are in. Nothing is read until " +
                    "you do, and nothing ever leaves your phone.",
                style = TrimTheme.typography.body.copy(color = colors.muted, textAlign = TextAlign.Center),
            )
            Box(
                modifier = Modifier
                    .pressScale(onChoose)
                    .background(colors.accent, RoundedCornerShape(TrimShape.BUTTON_DP.dp))
                    .padding(horizontal = TrimSpacing.CARD_PADDING_DP.dp, vertical = BUTTON_V_DP.dp),
            ) {
                BasicText(
                    text = "Choose folder",
                    style = TrimTheme.typography.label.copy(color = colors.accentOn),
                )
            }
        }
    }
}

private const val BUTTON_V_DP = 12
