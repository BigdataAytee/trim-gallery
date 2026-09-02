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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.trimgallery.engine.android.CrashReports
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.NightPass
import app.trimgallery.engine.android.StartupGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
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
    chrome: @Composable BoxScope.() -> Unit = {},
    storage: LibraryStorage = koinInject(),
    repository: TrimRepository = koinInject(),
    folders: GrantedFolders = koinInject(),
    nightPass: NightPass = koinInject(),
    guard: StartupGuard = koinInject(),
    crashes: CrashReports = koinInject(),
) {
    var grants by remember { mutableStateOf(folders.grants()) }
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var scanning by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Distinguishes "the database is still opening" from "there is genuinely nothing here",
    // so the first frame is not an empty-state message that is about to be wrong.
    var loaded by remember { mutableStateOf(false) }

    val picker = rememberFolderPicker(folders, nightPass) { grants = it }

    // What is already known, before any folder is walked. This is the whole of the fast
    // start: the rows were written the last time the app ran, and reading them is one
    // indexed query rather than a walk of every granted tree.
    LaunchedEffect(Unit) {
        items = repository.gallery()
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

    Box(modifier.fillMaxSize().background(TrimTheme.colors.page)) {
        when {
            grants.isEmpty() && loaded -> NoFolderYet { picker.choose() }
            items.isEmpty() -> ScanState(scanning = scanning, failure = failure) { picker.choose() }
            else -> LibrarySummary(items = items, scanning = scanning, onChoose = { picker.choose() })
        }
        chrome()
    }
}

/**
 * What is in the granted folders, in two numbers.
 *
 * The count and the total are the only things this screen can say honestly today. What
 * *could* be saved is a per-file question the probe and the predictor answer, and Big files
 * is the screen that asks it — inventing an estimate here would be the "saves about 200 MB"
 * that turns into 40 MB, on the first screen the user ever sees.
 */
@Composable
private fun LibrarySummary(items: List<MediaItem>, scanning: Boolean, onChoose: () -> Unit) {
    val colors = TrimTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
        modifier = Modifier.fillMaxSize().padding(TrimSpacing.INSET_DP.dp),
    ) {
        BasicText(
            text = MediaFormatting.bytes(items.sumOf { it.size }),
            style = TrimTheme.typography.title.copy(color = colors.text),
        )
        BasicText(
            text = "${items.size} files in your granted folders" +
                if (scanning) " · still looking" else "",
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )
        Box(modifier = Modifier.pressScale(onChoose)) {
            BasicText(
                text = "Add another folder",
                style = TrimTheme.typography.label.copy(color = colors.accent),
            )
        }
    }
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

/**
 * What Home shows while it has nothing to show: scanning, or a scan that failed, or a
 * granted folder that genuinely holds no media.
 */
@Composable
private fun ScanState(scanning: Boolean, failure: String?, onChoose: () -> Unit) {
    val colors = TrimTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(TrimSpacing.INSET_DP.dp),
        modifier = Modifier.padding(TrimSpacing.INSET_DP.dp),
    ) {
        BasicText(
            text = when {
                failure != null -> "That folder could not be read: $failure"
                scanning -> "Looking through your folder…"
                else -> "Nothing here that this app can work with yet."
            },
            style = TrimTheme.typography.body.copy(color = colors.muted),
        )
        if (!scanning) {
            Box(modifier = Modifier.pressScale(onChoose)) {
                BasicText(
                    text = "Choose another folder",
                    style = TrimTheme.typography.label.copy(color = colors.accent),
                )
            }
        }
    }
}

private const val BUTTON_V_DP = 12
