package app.trimgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.trimgallery.core.data.TrimRepository
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.domain.skip.SkipList
import app.trimgallery.core.ui.motion.pressScale
import app.trimgallery.core.ui.theme.TrimTheme
import app.trimgallery.engine.android.NightPassStatus
import app.trimgallery.engine.android.WorkManagerScheduler
import app.trimgallery.feature.space.SpaceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Instant
import app.trimgallery.core.domain.space.History as HistoryRules
import app.trimgallery.core.domain.space.SpaceScreen as SpaceRules

/**
 * Space, bound to the database and to what WorkManager currently holds.
 *
 * Everything on the screen is read once when it opens rather than observed. The numbers it
 * shows change when a night pass runs, which is at 3am on a charger — not while somebody is
 * looking at them — so a Flow per figure would be five subscriptions kept alive to deliver
 * nothing.
 */
@Composable
fun SpaceHost(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: TrimRepository = koinInject(),
    scheduler: WorkManagerScheduler = koinInject(),
) {
    val colors = TrimTheme.colors
    var snapshot by remember { mutableStateOf<SpaceSnapshot?>(null) }

    LaunchedEffect(Unit) { snapshot = readSpace(repository, scheduler) }

    Box(modifier.fillMaxSize().background(colors.page)) {
        snapshot?.let { space ->
            SpaceScreen(
                state = space.state,
                history = space.history,
                skipped = space.skipped,
                nextRun = space.nextRun,
                formatDate = ::formatDate,
                // Restore is not wired yet, and the button is drawn from
                // `History.isOneTap`, which only ever says yes for a row that came from a
                // job — and no job rows exist. So this cannot currently be reached; when
                // the replace path starts writing them it becomes `UndoBinAndroid.restore`.
                onRestore = {},
                onSecondaryAction = {},
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
                header = { BackToPhotos(onBack) },
                artwork = { Thumbnail(it) },
            )
        }
    }
}

@Composable
private fun BackToPhotos(onBack: () -> Unit) {
    BasicText(
        text = "← Photos",
        style = TrimTheme.typography.label.copy(color = TrimTheme.colors.accent),
        modifier = Modifier.pressScale(onBack),
    )
}

/** Everything the screen needs, read together so the figures on it agree with each other. */
private data class SpaceSnapshot(
    val state: SpaceRules.State,
    val history: List<HistoryRules.Row>,
    val skipped: List<SkipList.Group>,
    val nextRun: String?,
)

/**
 * One pass over the database, off the main thread.
 *
 * Read together rather than field by field for a reason the screen would otherwise show:
 * the total freed and the progress ring come from the same rows, and reading them in two
 * queries a second apart lets a run that finishes in between produce a screen where the
 * ring says "working" and the total already includes what it just did.
 */
private suspend fun readSpace(repository: TrimRepository, scheduler: WorkManagerScheduler): SpaceSnapshot =
    withContext(Dispatchers.IO) {
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        val sessions = repository.runSessions()
        val jobs = repository.succeededJobs()
        val items = repository.skipped()

        SpaceSnapshot(
            state = SpaceRules.state(
                sessions = sessions,
                current = repository.currentRunSession(),
                queueSize = repository.candidateCount(),
                projectedSaving = repository.projectedSaving(),
                // Free until billing is wired; MONETIZATION.md's cap is then read from the
                // same Entitlements the rest of the app uses rather than assumed here.
                tier = Tier.FREE,
                now = now,
                monthStart = monthStart(now, zone),
                nextMonthStart = nextMonthStart(now, zone),
            ),
            history = HistoryRules.rows(
                jobs = jobs,
                // Empty until jobs exist. `History.rows` drops a job whose media row is
                // missing, so a mismatch here shows nothing rather than a row with no name.
                items = emptyMap(),
                undoByMedia = repository.undoByMedia(),
                now = now,
            ),
            skipped = SkipList.groups(items),
            nextRun = nextRunLine(scheduler.status()),
        )
    }

/**
 * What the screen says about the next run.
 *
 * WorkManager gives a state, not a time: a periodic request has a flex window and the OS
 * places the run inside it. So this says what is true — that it is scheduled and when it
 * can happen — rather than a clock time the app would be guessing.
 */
private fun nextRunLine(status: NightPassStatus): String? = when {
    !status.scheduled -> null
    status.runAttempts > 0 ->
        "Scheduled, but the last run did not finish. Export diagnostics from Settings has the detail."
    else -> "Scheduled for tonight, once the phone is charging and idle."
}

/**
 * The user's own calendar month, not UTC's.
 *
 * MONETIZATION.md's cap resets on the month the user is living in; computing it in UTC
 * would reset it a few hours early or late for most of the world, which on the last day of
 * a month is the difference between a cap that has reset and one that has not.
 */
private fun monthStart(now: Instant, zone: TimeZone): Instant {
    val today = now.toLocalDateTime(zone).date
    return LocalDate(today.year, today.monthNumber, 1).atStartOfDayIn(zone)
}

private fun nextMonthStart(now: Instant, zone: TimeZone): Instant {
    val today = now.toLocalDateTime(zone).date
    val rollsOver = today.monthNumber == DECEMBER
    val year = if (rollsOver) today.year + 1 else today.year
    val month = if (rollsOver) 1 else today.monthNumber + 1
    return LocalDate(year, month, 1).atStartOfDayIn(zone)
}

/**
 * A date, in the phone's own zone. Days only: the hour of a restore window is noise.
 *
 * Spelled out here rather than shared with the grid's headers, which have their own
 * private copy in `DateSections`. Two copies of a month list is one too many and the right
 * home is `core/ui/format` beside `MediaFormatting`; recorded in PROJECT.md rather than
 * done in a change about the Space screen.
 */
private fun formatDate(instant: Instant): String {
    val date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${date.dayOfMonth} ${MONTHS[date.monthNumber - 1]} ${date.year}"
}

private const val DECEMBER = 12

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
