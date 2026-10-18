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
        /** The pay screen, where 연차 is. */
        LEAVE,
        /** Where the alarm's permissions are checked and fixed. */
        ALARM_CHECK,
        /** [SLP-16] The sleep log and what it says. */
        SLEEP,
        /** [RECV-02] Everything owed to the user. */
        OWED,
        /** [IMPORT-01] Bringing in the phone's calendar. */
        CALENDAR_IMPORT,
    }

    private const val EXTRA = "com.bamnat.app.extra.OPEN"

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target.asStateFlow()

    /** Opens the app on [target], or brings it forward there; the screen underneath is not stacked again. */
    fun intent(context: Context, target: Target): Intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA, target.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Reads a target off an intent; true when there was one. */
    fun read(intent: Intent?): Boolean {
        // Reopened from Recents, Android hands back the intent the app was first opened with: it was followed then.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        val target = intent.getStringExtra(EXTRA)?.let { n -> Target.entries.firstOrNull { it.name == n } } ?: return false
        intent.removeExtra(EXTRA)
        _target.value = target
        return true
    }

    /** Opens [target] from inside the app, from a screen that has no way there of its own. */
    fun open(target: Target) {
        _target.value = target
    }

    fun consume(target: Target) {
        _target.compareAndSet(target, null)
    }
}
