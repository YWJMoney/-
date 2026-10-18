package com.bamnat.app.data

import android.content.Context
import android.content.SharedPreferences
import com.bamnat.app.tier.Tier
import com.bamnat.core.hours.HourLimits
import com.bamnat.core.work.WorkStyle
import com.bamnat.core.model.ExtraKinds
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
    /** Lunar dates in calendar cells and the day header [FEAT-05]. */
    val showLunar: Boolean = false,
    /** [OPS-20] A happy moment happened (first pattern saved); ask for a review once. */
    val reviewDue: Boolean = false,
    val reviewAsked: Boolean = false,
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
    /** [WORK-01] How the user works; null until chosen on first launch. */
    val workStyle: WorkStyle? = null,
    /** [PAYX-09][EXTRA-01] The kinds of extra work this person's company has, with their times. */
    val extraKinds: ExtraKinds = ExtraKinds(),
    /** [FEAT-17] Memos come first in the day view, for people who live by their notes. */
    val memoFirst: Boolean = false,
    /** [FEAT-17] The month's memos in short under the calendar. */
    val calendarMemos: Boolean = true,
    /** [SLP-01][MKT-07] Rest-gap signs on the calendar and the day view. */
    val restCheck: Boolean = true,
    /** [MKT-11] Delphi rules on top: at most 2 nights in a row, 2 days off after nights. */
    val strictRest: Boolean = false,
    /** [SLP-03][SLP-14] 오늘의 흐름 on the calendar, and sleep times that fit the schedule in the day view. */
    val sleepTips: Boolean = true,
    /** [SLP-04][SLP-07] Notifications for those times; off until the user turns them on. */
    val sleepReminders: Boolean = false,
    /** [SLP-03][SLP-06] Hours of sleep the user aims for. */
    val sleepTargetMinutes: Int = 7 * 60,
    /** [SLP-14] Minutes from the end of work to bed: the way home. */
    val commuteMinutes: Int = 60,
    /** [SLP-08] Read sleep sessions from Health Connect; off until the user allows it. */
    val healthSleep: Boolean = false,
    /** [EXTRA-05] The user answered the offer to turn old shift-swap 대근 into 대근 with times. */
    val swapCoversAnswered: Boolean = false,
    /** [EXTRA-05] The user saw what the law would pay for work on days off and chose. */
    val restDayCompared: Boolean = false,
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
            showLunar = prefs.getBoolean(K_LUNAR, d.showLunar),
            reviewDue = prefs.getBoolean(K_REVIEW_DUE, false),
            reviewAsked = prefs.getBoolean(K_REVIEW_ASKED, false),
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
            workStyle = prefs.getString(K_WORK_STYLE, null)?.let { n -> WorkStyle.entries.firstOrNull { it.name == n } },
            extraKinds = ExtraKinds.decode(prefs.getString(K_EXTRA_KINDS, null)),
            memoFirst = prefs.getBoolean(K_MEMO_FIRST, d.memoFirst),
            calendarMemos = prefs.getBoolean(K_CAL_MEMOS, d.calendarMemos),
            restCheck = prefs.getBoolean(K_REST_CHECK, d.restCheck),
            strictRest = prefs.getBoolean(K_STRICT_REST, d.strictRest),
            sleepTips = prefs.getBoolean(K_SLEEP_TIPS, d.sleepTips),
            sleepReminders = prefs.getBoolean(K_SLEEP_REMINDERS, d.sleepReminders),
            sleepTargetMinutes = prefs.getInt(K_SLEEP_TARGET, d.sleepTargetMinutes),
            commuteMinutes = prefs.getInt(K_COMMUTE, d.commuteMinutes),
            healthSleep = prefs.getBoolean(K_HEALTH_SLEEP, d.healthSleep),
            swapCoversAnswered = prefs.getBoolean(K_SWAP_COVERS, d.swapCoversAnswered),
            restDayCompared = prefs.getBoolean(K_REST_COMPARED, d.restDayCompared),
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
            .putBoolean(K_LUNAR, s.showLunar)
            .putBoolean(K_REVIEW_DUE, s.reviewDue)
            .putBoolean(K_REVIEW_ASKED, s.reviewAsked)
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
            .putString(K_WORK_STYLE, s.workStyle?.name)
            .putString(K_EXTRA_KINDS, s.extraKinds.encode())
            .putBoolean(K_MEMO_FIRST, s.memoFirst)
            .putBoolean(K_CAL_MEMOS, s.calendarMemos)
            .putBoolean(K_REST_CHECK, s.restCheck)
            .putBoolean(K_STRICT_REST, s.strictRest)
            .putBoolean(K_SLEEP_TIPS, s.sleepTips)
            .putBoolean(K_SLEEP_REMINDERS, s.sleepReminders)
            .putInt(K_SLEEP_TARGET, s.sleepTargetMinutes)
            .putInt(K_COMMUTE, s.commuteMinutes)
            .putBoolean(K_HEALTH_SLEEP, s.healthSleep)
            .putBoolean(K_SWAP_COVERS, s.swapCoversAnswered)
            .putBoolean(K_REST_COMPARED, s.restDayCompared)
            .apply()
    }

    private companion object {
        const val K_WEEK_START = "week_start"
        const val K_WEEKLY_LIMIT = "weekly_limit"
        const val K_AVG_LIMIT = "average_limit"
        const val K_THEME = "theme"
        const val K_DYNAMIC = "dynamic_color"
        const val K_CELL_HOURS = "cell_hours"
        const val K_LUNAR = "lunar"
        const val K_REVIEW_DUE = "review_due"
        const val K_REVIEW_ASKED = "review_asked"
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
        const val K_WORK_STYLE = "work_style"
        const val K_EXTRA_KINDS = "extra_kinds"
        const val K_MEMO_FIRST = "memo_first"
        const val K_CAL_MEMOS = "calendar_memos"
        const val K_REST_CHECK = "rest_check"
        const val K_STRICT_REST = "strict_rest"
        const val K_SLEEP_TIPS = "sleep_tips"
        const val K_SLEEP_REMINDERS = "sleep_reminders"
        const val K_SLEEP_TARGET = "sleep_target"
        const val K_COMMUTE = "commute_minutes"
        const val K_HEALTH_SLEEP = "health_sleep"
        const val K_SWAP_COVERS = "swap_covers_answered"
        const val K_REST_COMPARED = "rest_day_compared"
    }
}
