package app.trimgallery.engine.android

import android.app.AlarmManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import app.trimgallery.core.model.Settings
import app.trimgallery.core.pipeline.night.AlarmWindow
import app.trimgallery.core.pipeline.night.GuardChain
import app.trimgallery.core.pipeline.night.NightConditions
import app.trimgallery.core.pipeline.night.NightFacts
import app.trimgallery.core.pipeline.night.ThermalGate
import app.trimgallery.engine.GuardResult
import app.trimgallery.engine.Guards
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Android readings behind the shared guards.
 *
 * This class only *reads*: every decision is `GuardChain`'s, which is platform-free and
 * unit tested. The split is deliberate — the ordering, the hysteresis and the caps are
 * where the bugs would be, and none of them are testable on a device without a fridge and
 * a very long night.
 *
 * @param facts the numbers only the database knows — settings, tier, queue sizes.
 * @param foreground whether the app is on screen. Supplied rather than observed here so
 *   the Application owns the one lifecycle callback and this stays a pure reader.
 * @param workedMs work done tonight, from the run's own budget. Not wall-clock time.
 */
class AndroidGuards(
    private val context: Context,
    private val facts: NightFacts,
    private val foreground: () -> Boolean,
    private val workedMs: () -> Long,
    private val chain: GuardChain = GuardChain(),
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : Guards {

    private val powerManager get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager get() = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val alarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val _thermalHeadroom = MutableStateFlow(Float.NaN)

    /** For the Space screen. Higher is hotter; NaN where the device cannot report it. */
    override val thermalHeadroom: StateFlow<Float> = _thermalHeadroom.asStateFlow()

    override val thermalPauses: Int get() = chain.thermal.pauseCount

    override suspend fun check(): GuardResult {
        val current = facts.settings()
        val headroom = readThermalHeadroom()
        _thermalHeadroom.value = headroom

        return chain.evaluate(
            conditions = NightConditions(
                now = clock.now(),
                appInForeground = foreground(),
                charging = batteryManager.isCharging,
                batteryFull = batteryLevel() >= FULL_PERCENT,
                thermalHeadroom = headroom,
                deadline = deadline(current),
                freeBytes = freeBytes(),
                largestPendingBytes = facts.largestPendingBytes(),
                workedMs = workedMs(),
                bytesFreedThisMonth = facts.bytesFreedThisMonth(),
                nextSavingBytes = facts.nextSavingBytes(),
            ),
            settings = current,
            tier = facts.tier(),
        )
    }

    /**
     * `getThermalHeadroom(30)`, forecasting 30 seconds ahead (BUILD.md § 11).
     *
     * The forecast rather than the instantaneous reading because an encode takes minutes:
     * by the time the current value crosses the threshold the phone is already hot, and
     * the point of pausing is to not get there.
     *
     * API 30 and above; below that, and on hardware with no thermal sensing, this returns
     * NaN and `ThermalGate` treats it as "no information" rather than as good news.
     * Calling it more often than once every 10 seconds also returns NaN, which the 5-second
     * poll would trip on — so the last real reading is kept.
     */
    private var lastRealHeadroom = Float.NaN

    private fun readThermalHeadroom(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        val reading = runCatching {
            powerManager.getThermalHeadroom(ThermalGate.FORECAST_SECONDS)
        }.getOrDefault(Float.NaN)

        // Documented behaviour: polled faster than every 10 s, the platform returns NaN.
        // Reporting that as "unknown" would silently disable the thermal guard on exactly
        // the schedule BUILD.md § 11 asks for.
        if (!reading.isNaN()) lastRealHeadroom = reading
        return if (reading.isNaN()) lastRealHeadroom else reading
    }

    private fun batteryLevel(): Int =
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /**
     * Free space on the volume the app's scratch directory lives on.
     *
     * The temp file is what actually needs the room — the encode writes there before
     * anything is committed — so measuring the granted folder's volume would answer the
     * wrong question on a phone with an SD card.
     */
    private fun freeBytes(): Long = runCatching {
        val stat = StatFs(context.cacheDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(Long.MAX_VALUE)

    /**
     * The earlier of "30 min before the next alarm" and the user's stop-by time.
     *
     * `getNextAlarmClock` returns only alarms set through `setAlarmClock` — which is what
     * clock apps use and what the user would call "my alarm" — and needs no permission.
     */
    private fun deadline(current: Settings): Instant? {
        val nextAlarm = runCatching {
            alarmManager.nextAlarmClock?.triggerTime?.let(Instant::fromEpochMilliseconds)
        }.getOrNull()

        val stopBy = current.stopByTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        return AlarmWindow.deadline(clock.now(), nextAlarm, stopBy, zone())
    }

    private companion object {
        /**
         * What counts as full.
         *
         * Not 100: many phones report 99 for a long time on a topped-up battery, and a
         * pass that waits for a number it may never see would simply never run
         * (BUILD.md § 11, "start when full", default on).
         */
        const val FULL_PERCENT = 98
    }
}
