package app.trimgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.domain.compress.OptimiseFlow
import app.trimgallery.core.domain.skip.SkipList
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.pipeline.TriageStep
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimSpacing
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.feature.compress.BigFilesScreen
import app.trimgallery.feature.compress.OptimiseSheet
import org.koin.compose.koinInject

/**
 * Big files: run triage over the granted folders, then show what it decided.
 *
 * **"Find big files" is `TriageStep.run`, not a second opinion.** The estimate on every row
 * is the Triager's own projection and the skip reasons are its own verdicts — the same
 * decisions the night pass acts on, written to the same rows. A screen that computed its
 * own numbers would eventually disagree with the thing that does the work, and the user
 * would be right to believe neither.
 *
 * Triage is re-run each time the screen opens. It reads container headers for files the
 * library diff marked new or changed, so a repeat visit over an unchanged library is cheap,
 * and a visit after the camera has been busy is exactly when the numbers should move.
 */
@Composable
@UnstableApi
fun BigFilesHost(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    folders: GrantedFolders = koinInject(),
    repository: TrimRepository = koinInject(),
    triage: TriageStep = koinInject(),
    optimise: OptimiseController = rememberOptimiseController(),
) {
    var candidates by remember { mutableStateOf(emptyList<MediaItem>()) }
    var skipped by remember { mutableStateOf(emptyList<SkipList.Group>()) }
    var scanning by remember { mutableStateOf(true) }
    // Bumped when an optimise finishes, so the list drops the file it just shrank.
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(revision) {
        scanning = true
        val grants = folders.grants()
        if (grants.isNotEmpty()) {
            // Deliberately not wrapped in runCatching. A triage failure is the app's own
            // work failing over a persisted grant, which is what StartupGuard exists for —
            // and swallowing it here would leave an empty screen with no explanation.
            triage.run(grants)
        }
        candidates = repository.candidates()
        skipped = SkipList.groups(repository.skipped())
        scanning = false
    }

    Box(modifier.fillMaxSize().background(TrimTheme.colors.page)) {
        BigFilesScreen(
            candidates = candidates,
            skipped = skipped,
            scanning = scanning,
            working = workingOn(optimise.state),
            onTrim = { item ->
                optimise.open(item)
                optimise.start()
            },
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            header = { BackToHome(onBack) },
        )
        OptimiseOverlay(optimise, onFinished = { revision++ })
    }
}

/**
 * The file currently being trimmed, as a set so the row can say so.
 *
 * A set of one, because the controller runs a single encode at a time — trimming several
 * at once would have them fighting for the one hardware encoder the phone has.
 */
private fun workingOn(state: OptimiseFlow.State): Set<String> = when (state) {
    is OptimiseFlow.State.Working -> setOf(state.item.id)
    else -> emptySet()
}

/**
 * The sheet over the list, and the one place a finished trim refreshes it.
 *
 * `onFinished` fires on Keep and on Undo rather than when the encode ends: the row should
 * leave the list when the user is done with it, not while they are still deciding — a list
 * that reshuffles under a sheet the user is reading is how a Keep becomes an Undo.
 */
@Composable
private fun BoxScope.OptimiseOverlay(optimise: OptimiseController, onFinished: () -> Unit) {
    if (optimise.state == OptimiseFlow.State.Closed) return

    OptimiseSheet(
        state = optimise.state,
        onStart = optimise::start,
        onKeep = {
            optimise.keep()
            onFinished()
        },
        onUndo = {
            optimise.undo()
            onFinished()
        },
        onDismiss = optimise::dismiss,
        modifier = Modifier.align(Alignment.BottomCenter).padding(TrimSpacing.INSET_DP.dp),
        // No thumbnail: Big files is a list of names and sizes, and the sheet matches it.
        // Coil and the thumbnail path go with the History screen's rebuild.
        artwork = {},
    )
}

@Composable
private fun BackToHome(onBack: () -> Unit) {
    BasicText(
        text = "← Home",
        style = TrimTheme.typography.label.copy(color = TrimTheme.colors.accent),
        modifier = Modifier.pressScale(onBack),
    )
}
