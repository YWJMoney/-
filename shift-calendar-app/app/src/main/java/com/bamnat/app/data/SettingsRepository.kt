package com.bamnat.app.data

import android.content.Context
import android.content.SharedPreferences
import com.bamnat.app.tier.Tier
import com.bamnat.core.hours.HourLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val weeklyLimitHours: Int = 64,
    val averageLimitHours: Int = 52,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val showHoursInCells: Boolean = true,
    /** Default alarm sound; null means the system alarm sound, [SILENT_SOUND] means vibrate only. */
    val defaultAlarmSound: String? = null,
    val vibrate: Boolean = true,
    val gradualVolume: Boolean = true,
    val snoozeMinutes: Int = 5,
    /** The alarm stops by itself after this many minutes. */
    val ringMinutes: Int = 10,
    val notificationPermissionAsked: Boolean = false,
    /** Master switch: off silences every alarm without losing any alarm settings. */
    val alarmsEnabled: Boolean = true,
    /** The user ticked "다시 표시하지 않기" on the notice shown when saving a day's alarms. */
    val hideDayAlarmNotice: Boolean = false,
    /** Opt-in: send anonymous usage statistics once a remote service is connected [MEAS-03]. */
    val shareUsageStats: Boolean = false,
    /** Subscription tier; set by billing later, by testers for now [TIER-01]. */
    val tier: Tier = Tier.FREE,
) {
    val limits: HourLimits get() = HourLimits(weeklyLimitHours, averageLimitHours)

    companion object {
        const val SILENT_SOUND = "silent"
    }
}

/**
 * Small settings store on SharedPreferences. Reads are synchronous so the very first frame
 * already uses the user's week start and theme.
 */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // Held in a field: SharedPreferences keeps listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        synchronized(this) {
            val next = transform(read())
            write(next)
            _settings.value = next
        }
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            weekStart = runCatching { DayOfWeek.valueOf(prefs.getString(K_WEEK_START, null) ?: "") }
                .getOrDefault(d.weekStart),
            weeklyLimitHours = prefs.getInt(K_WEEKLY_LIMIT, d.weeklyLimitHours),
            averageLimitHours = prefs.getInt(K_AVG_LIMIT, d.averageLimitHours),
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString(K_THEME, null) ?: "") }
                .getOrDefault(d.themeMode),
            dynamicColor = prefs.getBoolean(K_DYNAMIC, d.dynamicColor),
            showHoursInCells = prefs.getBoolean(K_CELL_HOURS, d.showHoursInCells),
            defaultAlarmSound = prefs.getString(K_SOUND, null),
            vibrate = prefs.getBoolean(K_VIBRATE, d.vibrate),
            gradualVolume = prefs.getBoolean(K_GRADUAL, d.gradualVolume),
            snoozeMinutes = prefs.getInt(K_SNOOZE, d.snoozeMinutes),
            ringMinutes = prefs.getInt(K_RING, d.ringMinutes),
            notificationPermissionAsked = prefs.getBoolean(K_NOTIF_ASKED, false),
            alarmsEnabled = prefs.getBoolean(K_ALARMS_ON, d.alarmsEnabled),
            hideDayAlarmNotice = prefs.getBoolean(K_HIDE_DAY_NOTICE, d.hideDayAlarmNotice),
            shareUsageStats = prefs.getBoolean(K_SHARE_STATS, d.shareUsageStats),
            tier = runCatching { Tier.valueOf(prefs.getString(K_TIER, null) ?: "") }.getOrDefault(d.tier),
        )
    }

    private fun write(s: AppSettings) {
        prefs.edit()
            .putString(K_WEEK_START, s.weekStart.name)
            .putInt(K_WEEKLY_LIMIT, s.weeklyLimitHours)
            .putInt(K_AVG_LIMIT, s.averageLimitHours)
            .putString(K_THEME, s.themeMode.name)
            .putBoolean(K_DYNAMIC, s.dynamicColor)
            .putBoolean(K_CELL_HOURS, s.showHoursInCells)
            .putString(K_SOUND, s.defaultAlarmSound)
            .putBoolean(K_VIBRATE, s.vibrate)
            .putBoolean(K_GRADUAL, s.gradualVolume)
            .putInt(K_SNOOZE, s.snoozeMinutes)
            .putInt(K_RING, s.ringMinutes)
            .putBoolean(K_NOTIF_ASKED, s.notificationPermissionAsked)
            .putBoolean(K_ALARMS_ON, s.alarmsEnabled)
            .putBoolean(K_HIDE_DAY_NOTICE, s.hideDayAlarmNotice)
            .putBoolean(K_SHARE_STATS, s.shareUsageStats)
            .putString(K_TIER, s.tier.name)
            .apply()
    }

    private companion object {
        const val K_WEEK_START = "week_start"
        const val K_WEEKLY_LIMIT = "weekly_limit"
        const val K_AVG_LIMIT = "average_limit"
        const val K_THEME = "theme"
        const val K_DYNAMIC = "dynamic_color"
        const val K_CELL_HOURS = "cell_hours"
        const val K_SOUND = "default_sound"
        const val K_VIBRATE = "vibrate"
        const val K_GRADUAL = "gradual"
        const val K_SNOOZE = "snooze"
        const val K_RING = "ring_minutes"
        const val K_NOTIF_ASKED = "notif_asked"
        const val K_ALARMS_ON = "alarms_on"
        const val K_HIDE_DAY_NOTICE = "hide_day_alarm_notice"
        const val K_SHARE_STATS = "share_usage_stats"
        const val K_TIER = "tier"
    }
}
