package com.bamnat.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SLP-15] Where a tapped notice opens: the screen it is about, not just the app. The activity
 * hands the target here and the navigation goes there once it can.
 */
object NoticeLinks {
    enum class Target {
        /** The calendar on today, with 오늘의 흐름 at the top. */
        TODAY,
        MONEY,
        /** [LEDG-16] The money tab with 자동 기록 (결제 알림 읽기) open. */
        AUTO_RECORD,
        /** The pay screen, where 연차 is. */
        LEAVE,
        /** Where the alarm's permissions are checked and fixed. */
        ALARM_CHECK,
        /** [SCHED-01] The memos, from a memo schedule's notice. */
        MEMOS,
        /** [SLP-16] The sleep log and what it says. */
        SLEEP,
        /** [RECV-02] Everything owed to the user. */
        OWED,
        /** [IMPORT-01] Bringing in the phone's calendar. */
        CALENDAR_IMPORT,
        /** [SHR-12] The calendar with the share sheet open, from the link's expiry notice. */
        SHARE,
        /** [UX-03] The calendar with the sheet that takes a received link open. */
        RECEIVE,
        /** [SHFT-06] 듀티표 사진으로 넣기, for the calendar on screen. */
        DUTY_ROSTER,
        /** [SHFT-07] 연간 건강 지표. */
        HEALTH_REPORT,
        /** [CREW-01] 모든 조 보기, for the calendar on screen. */
        TEAMS,
        /** [CAL-22] 1년 달력, for the calendar on screen. */
        YEAR,
        /** [DAYW-06] 근태 엑셀 가져오기. */
        ATTENDANCE_IMPORT,
        /** [DAYW-07] 출퇴근 자동 기록, from its settings row or its notice. */
        COMMUTE,
        /** [INV-01] The investor calendar's 투자 screen. */
        INVEST,
        /** [INV-08] The investor calendar with the trade sheet open, filled in from the alarm in [payload]. */
        INVEST_TRADE,
    }

    private const val EXTRA = "com.bamnat.app.extra.OPEN"

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target.asStateFlow()

    private const val CALENDAR_EXTRA = "com.bamnat.app.extra.CALENDAR"

    private val _calendar = MutableStateFlow<String?>(null)
    /** [MCAL-03] Which of the user's calendars [target] is about, when a notice says; null is the main one. */
    val calendar: StateFlow<String?> = _calendar.asStateFlow()

    private const val PAYLOAD_EXTRA = "com.bamnat.app.extra.PAYLOAD"

    private val _payload = MutableStateFlow<String?>(null)
    /** [INV-08] What [target] opens on, when a notice says: an investor alarm's id for [Target.INVEST_TRADE]. */
    val payload: StateFlow<String?> = _payload.asStateFlow()

    /** Opens the app on [target], or brings it forward there; the screen underneath is not stacked again. */
    fun intent(context: Context, target: Target, calendar: String? = null, payload: String? = null): Intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA, target.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply {
            if (payload != null) {
                putExtra(PAYLOAD_EXTRA, payload)
                // [INV-08] Each alarm's notice keeps its own intent too.
                data = android.net.Uri.parse("bamnat-notice://${target.name.lowercase()}/${calendar ?: "main"}/$payload")
            }
            if (calendar == null) return@apply
            putExtra(CALENDAR_EXTRA, calendar)
            // Each calendar's notice keeps its own intent rather than taking over another's.
            if (payload == null) data = android.net.Uri.parse("bamnat-notice://calendar/$calendar")
        }

    /** Reads a target off an intent; true when there was one. */
    fun read(intent: Intent?): Boolean {
        // Reopened from Recents, Android hands back the intent the app was first opened with: it was followed then.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        val target = intent.getStringExtra(EXTRA)?.let { n -> Target.entries.firstOrNull { it.name == n } } ?: return false
        intent.removeExtra(EXTRA)
        _calendar.value = intent.getStringExtra(CALENDAR_EXTRA)
        intent.removeExtra(CALENDAR_EXTRA)
        _payload.value = intent.getStringExtra(PAYLOAD_EXTRA)
        intent.removeExtra(PAYLOAD_EXTRA)
        _target.value = target
        return true
    }

    /**
     * Opens [target] from inside the app, from a screen that has no way there of its own; [calendar]
     * names one of the user's calendars when it is about one [UX-03].
     */
    fun open(target: Target, calendar: String? = null, payload: String? = null) {
        _calendar.value = calendar
        _payload.value = payload
        _target.value = target
    }

    fun consume(target: Target) {
        _target.compareAndSet(target, null)
    }
}
