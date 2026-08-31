package app.trimgallery.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.domain.settings.SettingsPolicy
import app.trimgallery.core.model.PhotoFormat
import app.trimgallery.core.model.QualityTarget
import app.trimgallery.core.model.Settings
import app.trimgallery.engine.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** ARCHITECTURE.md § 12: one store, named once. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "trim_settings")

/**
 * The settings store on Android (STACK.md: `androidx.datastore:datastore-preferences`).
 *
 * It holds one rule that is easy to lose in a file that is otherwise key-value plumbing:
 *
 * **Nothing enters or leaves without going through `SettingsPolicy.sanitise`.**
 *
 * On the way in, because a value that cannot be honoured must never be persisted — a
 * stop-by time of "25:00" that is dropped at read time looks to the user like a setting
 * that does not work. On the way *out*, because entitlements change after the write: a Pro
 * user who set Compact and 90-day retention and then lapses must get Standard and 30 days
 * from the very next read, without anything having to remember to re-save their settings.
 *
 * Unknown or corrupt stored values fall back to the documented default rather than
 * throwing. A settings file is the one piece of state that, if it fails to load, takes the
 * whole app with it — and it is also the one the user can most easily do without.
 */
class DataStoreSettings(private val store: DataStore<Preferences>, private val tier: suspend () -> Tier) :
    SettingsStore {

    constructor(context: Context, tier: suspend () -> Tier) : this(context.settingsDataStore, tier)

    override val settings: Flow<Settings> =
        store.data.map { SettingsPolicy.sanitise(it.toSettings(), tier()) }

    override suspend fun read(): Settings = settings.first()

    override suspend fun update(transform: (Settings) -> Settings): Settings {
        val currentTier = tier()
        val written = store.edit { prefs ->
            val next = SettingsPolicy.sanitise(transform(prefs.toSettings()), currentTier)
            next.writeInto(prefs)
        }
        // Sanitised again on the way out for the same reason every read is: the tier is the
        // authority, and it is read fresh rather than cached.
        return SettingsPolicy.sanitise(written.toSettings(), currentTier)
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            qualityTarget = enumOr(this[Keys.QUALITY], defaults.qualityTarget, QualityTarget.entries),
            photoFormat = enumOr(this[Keys.PHOTO_FORMAT], defaults.photoFormat, PhotoFormat.entries),
            photoReversible = this[Keys.PHOTO_REVERSIBLE] ?: defaults.photoReversible,
            nightlyCapMinutes = this[Keys.CAP_MINUTES] ?: defaults.nightlyCapMinutes,
            undoRetentionDays = this[Keys.RETENTION_DAYS] ?: defaults.undoRetentionDays,
            allowAv1 = this[Keys.ALLOW_AV1] ?: defaults.allowAv1,
            carefulVerify = this[Keys.CAREFUL_VERIFY] ?: defaults.carefulVerify,
            startWhenFull = this[Keys.START_WHEN_FULL] ?: defaults.startWhenFull,
            keepWorkingWhileUsing = this[Keys.KEEP_WORKING] ?: defaults.keepWorkingWhileUsing,
            faceClusteringEnabled = this[Keys.FACE_CLUSTERING] ?: defaults.faceClusteringEnabled,
            stopByTime = this[Keys.STOP_BY],
        )
    }

    private fun Settings.writeInto(prefs: MutablePreferences) {
        prefs[Keys.QUALITY] = qualityTarget.name
        prefs[Keys.PHOTO_FORMAT] = photoFormat.name
        prefs[Keys.PHOTO_REVERSIBLE] = photoReversible
        prefs[Keys.CAP_MINUTES] = nightlyCapMinutes
        prefs[Keys.RETENTION_DAYS] = undoRetentionDays
        prefs[Keys.ALLOW_AV1] = allowAv1
        prefs[Keys.CAREFUL_VERIFY] = carefulVerify
        prefs[Keys.START_WHEN_FULL] = startWhenFull
        prefs[Keys.KEEP_WORKING] = keepWorkingWhileUsing
        prefs[Keys.FACE_CLUSTERING] = faceClusteringEnabled
        // Removed rather than written as empty: absent means "no stop time", and an empty
        // string that parses to nothing would be a second way to say the same thing.
        //
        // Bound to a local rather than smart-cast in the `else`: `stopByTime` is a public API
        // property of `Settings`, which lives in `core.model` — a different module, so Kotlin
        // will not narrow it, because that module could add a custom getter without this one
        // recompiling. Same error as `TriageStep`'s latitude/longitude, in the one source set
        // no harness here can compile.
        val stopBy = stopByTime
        if (stopBy == null) prefs.remove(Keys.STOP_BY) else prefs[Keys.STOP_BY] = stopBy
    }

    /**
     * An enum name that is no longer one of ours becomes the default.
     *
     * Reachable by downgrading the app after a release added a value — rare, but the
     * alternative is a `valueOf` throwing inside a flow the whole app collects.
     */
    private fun <T : Enum<T>> enumOr(stored: String?, default: T, values: List<T>): T =
        values.firstOrNull { it.name == stored } ?: default

    /** The ARCHITECTURE.md § 12 key list, spelled once. */
    private object Keys {
        val QUALITY = stringPreferencesKey("qualityTarget")
        val PHOTO_FORMAT = stringPreferencesKey("photoFormat")
        val PHOTO_REVERSIBLE = booleanPreferencesKey("photoReversible")
        val CAP_MINUTES = intPreferencesKey("nightlyCapMinutes")
        val RETENTION_DAYS = intPreferencesKey("undoRetentionDays")
        val ALLOW_AV1 = booleanPreferencesKey("allowAv1")
        val CAREFUL_VERIFY = booleanPreferencesKey("carefulVerify")
        val START_WHEN_FULL = booleanPreferencesKey("startWhenFull")
        val KEEP_WORKING = booleanPreferencesKey("keepWorkingWhileUsing")
        val FACE_CLUSTERING = booleanPreferencesKey("faceClusteringEnabled")
        val STOP_BY = stringPreferencesKey("stopByTime")
    }
}
