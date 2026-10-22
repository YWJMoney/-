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

enum class ThemeMode {
    SYSTEM, LIGHT, DARK,
    /** [SHFT-05] Dark while the calendar tab's calendar is in a night shift, the phone's theme otherwise. */
    NIGHT_SHIFT,
}

/** [CAL-03] What a tap on a date does on a phone-sized screen (unfolded, the day is always beside the calendar). */
enum class PhoneDayView {
    /** The day in short under the calendar; a second tap opens it all in a sheet. As before 1.13. */
    PREVIEW,
    /** The whole day in a sheet at once. */
    SHEET,
    /** The month folds to the tapped date's week, Monday to Sunday, with the whole day under it. */
    WEEK,
}

data class AppSettings(
    /** [CAL-02] Sunday first, like a paper calendar; the user can still pick Monday. */
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
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
    /** Master switch: off silences every alarm without losing any alarm settings. */
    val alarmsEnabled: Boolean = true,
    /** The user ticked "다시 표시하지 않기" on the notice shown when saving a day's alarms. */
    val hideDayAlarmNotice: Boolean = false,
    /** Opt-in: send anonymous usage statistics once a remote service is connected [MEAS-03]. */
    val shareUsageStats: Boolean = false,
    /**
     * [MON-02] A tester's preview of the screens after launch (alarm check screen): null shows the real
     * state. It counts before the 정식 출시 switch and in debug builds only. The old "tier" key, saved as
     * FREE for everyone, is not read, so nobody sees locks they did not choose.
     */
    val previewTier: Tier? = null,
    /**
     * [WORK-01] How the user works; null until chosen on first launch. [SETUP-01] The first setup
     * writes it only when it saves, so null also means the first setup is not done.
     */
    val workStyle: WorkStyle? = null,
    /** [SETUP-02] Shift work on a roster that comes every month: days are painted, not repeated. */
    val rosterMonthly: Boolean = false,
    /** [PAYX-09][EXTRA-01] The kinds of extra work this person's company has, with their times. */
    val extraKinds: ExtraKinds = ExtraKinds(),
    /**
     * [FEAT-17][DAY-04] The start of the day's first memo under the date in each calendar cell. Up to 1.12 this
     * also put 메모 first in the day view; that is now the user's own [dayGroupOrder].
     */
    val memoInCells: Boolean = false,
    /** [FEAT-17] The month's memos in short under the calendar. */
    val calendarMemos: Boolean = true,
    /** [SLP-01][MKT-07] Rest-gap signs on the calendar and the day view. */
    val restCheck: Boolean = true,
    /** [MKT-11] Delphi rules on top: at most 2 nights in a row, 2 days off after nights. */
    val strictRest: Boolean = false,
    /** [SLP-03][SLP-14] 오늘의 흐름 on the calendar, and sleep times that fit the schedule in the day view. */
    val sleepTips: Boolean = true,
    /**
     * [SLP-15] 하루 브리핑: one silent notice for work that needs planning. Off until the user turns it
     * on; on for those who had the sleep notices of 1.10 and before on.
     */
    /** [FEAT-18] 잠금 화면 오늘 근무: a quiet notice that stays with today's and tomorrow's shift. */
    val todayShiftNotice: Boolean = false,
    val briefing: Boolean = false,
    /** [SLP-15] 퇴근길 안전: the one sound when a night or a 24-hour duty ends. Like [briefing]. */
    val commuteNotice: Boolean = false,
    /** [SLP-15] 잘 준비: a silent notice 30 minutes before the night before an early start; off unless asked for. */
    val bedtimeNotice: Boolean = false,
    /** [SLP-15] 자는 중: after "잠들었어요", a notice that stays with the 깼어요 button. */
    val sleepingNotice: Boolean = true,
    /** [SLP-15] Money and 연차 notices due while the user sleeps come 30 minutes after waking. */
    val quietWhileAsleep: Boolean = true,
    /** [SLP-15] 나갈 돈·받을 돈 notices; each fixed cost and job still has its own switch. */
    val moneyNotices: Boolean = true,
    /** [SLP-15][PAY-12] 연차 소멸 안내 on 11/1 and 12/1. */
    val leaveNotice: Boolean = true,
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
    /** [CAL-03] What a tap on a date does on a phone. */
    val phoneDayView: PhoneDayView = PhoneDayView.SHEET,
    /**
     * [CAL-20] The small day card under the calendar (its times, 특근, 알람 끄기). Off unless turned on: the
     * day sheet does all of it. On, the first tap shows the card and a second opens the day.
     */
    val showDayCard: Boolean = false,
    /** [LOCK-01] 앱 잠금: the phone's own lock before the app shows, on opening and after a minute away. */
    val appLock: Boolean = false,
    /** [CUST-01] 화면 꾸미기: the calendar page's parts in the user's order, "hours,-todayFlow" ([com.bamnat.core.layout.PageLayout]). */
    val calendarLayout: String = "",
    /** [CUST-01] The same for the 근무·급여 (통계) page. */
    val statsLayout: String = "",
    /**
     * [DAY-04] The day view's groups in the user's own order, by key ("work", "memo", ...); empty means the
     * order that fits [workStyle]. Keys the app does not know are skipped, missing ones go at the end.
     */
    val dayGroupOrder: List<String> = emptyList(),
    /** [FEAT-07] The user's calendars written to a calendar of their own on the phone (프로); none at first. */
    val phoneCalendars: List<String> = emptyList(),
    /** [PROV-06] What 끄기 asks for (프로), by [com.bamnat.core.alarm.AlarmMission.key]; none at first. */
    val alarmMission: String = "none",
    /** [PROV-06] A shift's own mission, by shift type id, in place of [alarmMission]. */
    val shiftMissions: Map<Long, String> = emptyMap(),
    /** [PROV-07] Minutes after 끄기 that 일어났나요? asks (프로); 0 is off, as at first. */
    val wakeCheckMinutes: Int = 0,
    /** [DAILY-01] The day's first memo line on the 오늘·내일 widget; on, and off from 설정 › 화면 since a home screen is seen by others. */
    val widgetMemo: Boolean = true,
    /** [BACK-01] Receipt and contract photos go in the backup file (결정 D13: on, off from the backup screen). */
    val backupPhotos: Boolean = true,
    /** [DAILY-15] 화면 꾸미기 of the 돈 page, as [calendarLayout]. */
    val moneyLayout: String = "",
    /** [DAILY-15] 화면 꾸미기 of the 메모 page. */
    val memoLayout: String = "",
    /** [DAILY-16] The 지역 시세 line on the pay screen, worked out on the phone only; off from that line. */
    val rateHint: Boolean = true,
    /** [DAILY-17] The second 리뷰 moment (first 급여 리포트) came, and when any review was last asked (epoch day, 0 for never). */
    val payReviewDue: Boolean = false,
    val payReviewAsked: Boolean = false,
    val reviewAskedDay: Long = 0,
) {
    val limits: HourLimits get() = HourLimits(weeklyLimitHours, averageLimitHours)

    companion object {
        const val SILENT_SOUND = "silent"
    }
}

/** [DAY-04] "memo,work" back to its keys; blank parts are dropped. */
fun decodeGroupOrder(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** [PROV-06] "3=math,5=shake": each shift's own mission; anything unreadable is left out. */
fun decodeShiftMissions(text: String): Map<Long, String> = text.split(',').mapNotNull { part ->
    val (id, key) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
    id.trim().toLongOrNull()?.let { it to key.trim() }?.takeIf { it.second.isNotEmpty() }
}.toMap()

fun encodeShiftMissions(map: Map<Long, String>): String = map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

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
            // [CAL-02] Up to 1.12 every save wrote Monday, chosen or not, so the old key is not read: everyone
            // starts on Sunday once, and Monday stays for those who pick it again.
            weekStart = runCatching { DayOfWeek.valueOf(prefs.getString(K_WEEK_START_V2, null) ?: "") }
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
            alarmsEnabled = prefs.getBoolean(K_ALARMS_ON, d.alarmsEnabled),
            hideDayAlarmNotice = prefs.getBoolean(K_HIDE_DAY_NOTICE, d.hideDayAlarmNotice),
            shareUsageStats = prefs.getBoolean(K_SHARE_STATS, d.shareUsageStats),
            previewTier = Tier.parse(prefs.getString(K_PREVIEW_TIER, null)),
            workStyle = prefs.getString(K_WORK_STYLE, null)?.let { n -> WorkStyle.entries.firstOrNull { it.name == n } },
            rosterMonthly = prefs.getBoolean(K_ROSTER_MONTHLY, d.rosterMonthly),
            extraKinds = ExtraKinds.decode(prefs.getString(K_EXTRA_KINDS, null)),
            memoInCells = prefs.getBoolean(K_MEMO_FIRST, d.memoInCells),
            calendarMemos = prefs.getBoolean(K_CAL_MEMOS, d.calendarMemos),
            restCheck = prefs.getBoolean(K_REST_CHECK, d.restCheck),
            strictRest = prefs.getBoolean(K_STRICT_REST, d.strictRest),
            sleepTips = prefs.getBoolean(K_SLEEP_TIPS, d.sleepTips),
            // The sleep notices of 1.10 and before carry over to the briefing and the commute notice.
            briefing = prefs.getBoolean(K_BRIEFING, prefs.getBoolean(K_SLEEP_REMINDERS, d.briefing)),
            todayShiftNotice = prefs.getBoolean(K_TODAY_SHIFT_NOTICE, d.todayShiftNotice),
            commuteNotice = prefs.getBoolean(K_COMMUTE_NOTICE, prefs.getBoolean(K_SLEEP_REMINDERS, d.commuteNotice)),
            bedtimeNotice = prefs.getBoolean(K_BEDTIME_NOTICE, d.bedtimeNotice),
            sleepingNotice = prefs.getBoolean(K_SLEEPING_NOTICE, d.sleepingNotice),
            quietWhileAsleep = prefs.getBoolean(K_QUIET, d.quietWhileAsleep),
            moneyNotices = prefs.getBoolean(K_MONEY_NOTICES, d.moneyNotices),
            leaveNotice = prefs.getBoolean(K_LEAVE_NOTICE, d.leaveNotice),
            sleepTargetMinutes = prefs.getInt(K_SLEEP_TARGET, d.sleepTargetMinutes),
            commuteMinutes = prefs.getInt(K_COMMUTE, d.commuteMinutes),
            healthSleep = prefs.getBoolean(K_HEALTH_SLEEP, d.healthSleep),
            swapCoversAnswered = prefs.getBoolean(K_SWAP_COVERS, d.swapCoversAnswered),
            restDayCompared = prefs.getBoolean(K_REST_COMPARED, d.restDayCompared),
            // [CAL-20] PREVIEW of 1.17 and before is now 바로 크게 with the card switch apart; the card starts off.
            phoneDayView = runCatching { PhoneDayView.valueOf(prefs.getString(K_PHONE_DAY_VIEW, null) ?: "") }
                .getOrDefault(d.phoneDayView).let { if (it == PhoneDayView.PREVIEW) PhoneDayView.SHEET else it },
            showDayCard = prefs.getBoolean(K_DAY_CARD, d.showDayCard),
            appLock = prefs.getBoolean(K_APP_LOCK, d.appLock),
            calendarLayout = prefs.getString(K_CALENDAR_LAYOUT, null) ?: d.calendarLayout,
            statsLayout = prefs.getString(K_STATS_LAYOUT, null) ?: d.statsLayout,
            // [DAY-04] 메모 중심 of 1.12 and before put 메모 first; that carries over as the user's own order.
            dayGroupOrder = prefs.getString(K_DAY_GROUP_ORDER, null)?.let(::decodeGroupOrder)
                ?: if (prefs.getBoolean(K_MEMO_FIRST, false)) listOf("memo") else d.dayGroupOrder,
            phoneCalendars = prefs.getString(K_PHONE_CALENDARS, null)?.let(::decodeGroupOrder) ?: d.phoneCalendars,
            alarmMission = prefs.getString(K_ALARM_MISSION, null) ?: d.alarmMission,
            shiftMissions = prefs.getString(K_SHIFT_MISSIONS, null)?.let(::decodeShiftMissions) ?: d.shiftMissions,
            wakeCheckMinutes = prefs.getInt(K_WAKE_CHECK, d.wakeCheckMinutes),
            widgetMemo = prefs.getBoolean(K_WIDGET_MEMO, d.widgetMemo),
            backupPhotos = prefs.getBoolean(K_BACKUP_PHOTOS, d.backupPhotos),
            moneyLayout = prefs.getString(K_MONEY_LAYOUT, null) ?: d.moneyLayout,
            memoLayout = prefs.getString(K_MEMO_LAYOUT, null) ?: d.memoLayout,
            rateHint = prefs.getBoolean(K_RATE_HINT, d.rateHint),
            payReviewDue = prefs.getBoolean(K_PAY_REVIEW_DUE, false),
            payReviewAsked = prefs.getBoolean(K_PAY_REVIEW_ASKED, false),
            reviewAskedDay = prefs.getLong(K_REVIEW_DAY, 0),
        )
    }

    private fun write(s: AppSettings) {
        prefs.edit()
            .putString(K_WEEK_START_V2, s.weekStart.name)
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
            .putBoolean(K_ALARMS_ON, s.alarmsEnabled)
            .putBoolean(K_HIDE_DAY_NOTICE, s.hideDayAlarmNotice)
            .putBoolean(K_SHARE_STATS, s.shareUsageStats)
            .putString(K_PREVIEW_TIER, s.previewTier?.name)
            .remove(K_OLD_TIER)
            .putString(K_WORK_STYLE, s.workStyle?.name)
            .putBoolean(K_ROSTER_MONTHLY, s.rosterMonthly)
            .putString(K_EXTRA_KINDS, s.extraKinds.encode())
            .putBoolean(K_MEMO_FIRST, s.memoInCells)
            .putBoolean(K_CAL_MEMOS, s.calendarMemos)
            .putBoolean(K_REST_CHECK, s.restCheck)
            .putBoolean(K_STRICT_REST, s.strictRest)
            .putBoolean(K_SLEEP_TIPS, s.sleepTips)
            .putBoolean(K_BRIEFING, s.briefing)
            .putBoolean(K_TODAY_SHIFT_NOTICE, s.todayShiftNotice)
            .putBoolean(K_COMMUTE_NOTICE, s.commuteNotice)
            .putBoolean(K_BEDTIME_NOTICE, s.bedtimeNotice)
            .putBoolean(K_SLEEPING_NOTICE, s.sleepingNotice)
            .putBoolean(K_QUIET, s.quietWhileAsleep)
            .putBoolean(K_MONEY_NOTICES, s.moneyNotices)
            .putBoolean(K_LEAVE_NOTICE, s.leaveNotice)
            .putInt(K_SLEEP_TARGET, s.sleepTargetMinutes)
            .putInt(K_COMMUTE, s.commuteMinutes)
            .putBoolean(K_HEALTH_SLEEP, s.healthSleep)
            .putBoolean(K_SWAP_COVERS, s.swapCoversAnswered)
            .putBoolean(K_REST_COMPARED, s.restDayCompared)
            .putString(K_PHONE_DAY_VIEW, s.phoneDayView.name)
            .putBoolean(K_DAY_CARD, s.showDayCard)
            .putBoolean(K_APP_LOCK, s.appLock)
            .putString(K_CALENDAR_LAYOUT, s.calendarLayout)
            .putString(K_STATS_LAYOUT, s.statsLayout)
            .putString(K_DAY_GROUP_ORDER, s.dayGroupOrder.joinToString(","))
            .putString(K_PHONE_CALENDARS, s.phoneCalendars.joinToString(","))
            .putString(K_ALARM_MISSION, s.alarmMission)
            .putString(K_SHIFT_MISSIONS, encodeShiftMissions(s.shiftMissions))
            .putInt(K_WAKE_CHECK, s.wakeCheckMinutes)
            .putBoolean(K_WIDGET_MEMO, s.widgetMemo)
            .putBoolean(K_BACKUP_PHOTOS, s.backupPhotos)
            .putString(K_MONEY_LAYOUT, s.moneyLayout)
            .putString(K_MEMO_LAYOUT, s.memoLayout)
            .putBoolean(K_RATE_HINT, s.rateHint)
            .putBoolean(K_PAY_REVIEW_DUE, s.payReviewDue)
            .putBoolean(K_PAY_REVIEW_ASKED, s.payReviewAsked)
            .putLong(K_REVIEW_DAY, s.reviewAskedDay)
            .apply()
    }

    private companion object {
        /** [CAL-02] Replaces "week_start", where 1.12 and before wrote Monday on every save. */
        const val K_WEEK_START_V2 = "week_start_v2"
        const val K_PHONE_DAY_VIEW = "phone_day_view"
        const val K_DAY_CARD = "day_card"
        const val K_APP_LOCK = "app_lock"
        const val K_CALENDAR_LAYOUT = "calendar_layout"
        const val K_STATS_LAYOUT = "stats_layout"
        const val K_DAY_GROUP_ORDER = "day_group_order"
        const val K_PHONE_CALENDARS = "phone_calendars"
        const val K_ALARM_MISSION = "alarm_mission"
        const val K_SHIFT_MISSIONS = "shift_missions"
        const val K_WAKE_CHECK = "wake_check_minutes"
        const val K_WIDGET_MEMO = "widget_memo"
        const val K_BACKUP_PHOTOS = "backup_photos"
        const val K_MONEY_LAYOUT = "money_layout"
        const val K_MEMO_LAYOUT = "memo_layout"
        const val K_RATE_HINT = "rate_hint"
        const val K_PAY_REVIEW_DUE = "pay_review_due"
        const val K_PAY_REVIEW_ASKED = "pay_review_asked"
        const val K_REVIEW_DAY = "review_asked_day"
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
        const val K_ALARMS_ON = "alarms_on"
        const val K_HIDE_DAY_NOTICE = "hide_day_alarm_notice"
        const val K_SHARE_STATS = "share_usage_stats"
        const val K_OLD_TIER = "tier"
        const val K_PREVIEW_TIER = "tier_preview"
        const val K_WORK_STYLE = "work_style"
        const val K_ROSTER_MONTHLY = "roster_monthly"
        const val K_EXTRA_KINDS = "extra_kinds"
        const val K_MEMO_FIRST = "memo_first"
        const val K_CAL_MEMOS = "calendar_memos"
        const val K_REST_CHECK = "rest_check"
        const val K_STRICT_REST = "strict_rest"
        const val K_SLEEP_TIPS = "sleep_tips"
        /** 1.10 and before: one switch for all sleep notices, read once to carry it over. */
        const val K_SLEEP_REMINDERS = "sleep_reminders"
        const val K_BRIEFING = "notice_briefing"
        const val K_TODAY_SHIFT_NOTICE = "notice_today_shift"
        const val K_COMMUTE_NOTICE = "notice_commute"
        const val K_BEDTIME_NOTICE = "notice_bedtime"
        const val K_SLEEPING_NOTICE = "notice_sleeping"
        const val K_QUIET = "notice_quiet_asleep"
        const val K_MONEY_NOTICES = "notice_money"
        const val K_LEAVE_NOTICE = "notice_leave"
        const val K_SLEEP_TARGET = "sleep_target"
        const val K_COMMUTE = "commute_minutes"
        const val K_HEALTH_SLEEP = "health_sleep"
        const val K_SWAP_COVERS = "swap_covers_answered"
        const val K_REST_COMPARED = "rest_day_compared"
    }
}
