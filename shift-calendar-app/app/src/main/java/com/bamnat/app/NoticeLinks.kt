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
    }

    private const val EXTRA = "com.bamnat.app.extra.OPEN"

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target.asStateFlow()

    private const val CALENDAR_EXTRA = "com.bamnat.app.extra.CALENDAR"

    private val _calendar = MutableStateFlow<String?>(null)
    /** [MCAL-03] Which of the user's calendars [target] is about, when a notice says; null is the main one. */
    val calendar: StateFlow<String?> = _calendar.asStateFlow()

    /** Opens the app on [target], or brings it forward there; the screen underneath is not stacked again. */
    fun intent(context: Context, target: Target, calendar: String? = null): Intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA, target.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply {
            if (calendar == null) return@apply
            putExtra(CALENDAR_EXTRA, calendar)
            // Each calendar's notice keeps its own intent rather than taking over another's.
            data = android.net.Uri.parse("bamnat-notice://calendar/$calendar")
        }

    /** Reads a target off an intent; true when there was one. */
    fun read(intent: Intent?): Boolean {
        // Reopened from Recents, Android hands back the intent the app was first opened with: it was followed then.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        val target = intent.getStringExtra(EXTRA)?.let { n -> Target.entries.firstOrNull { it.name == n } } ?: return false
        intent.removeExtra(EXTRA)
        _calendar.value = intent.getStringExtra(CALENDAR_EXTRA)
        intent.removeExtra(CALENDAR_EXTRA)
        _target.value = target
        return true
    }

    /** Opens [target] from inside the app, from a screen that has no way there of its own. */
    fun open(target: Target) {
        _calendar.value = null
        _target.value = target
    }

    fun consume(target: Target) {
        _target.compareAndSet(target, null)
    }
}
