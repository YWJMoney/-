package com.bamnat.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.bamnat.app.appContainer
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Redraws every widget and keeps a wake-up for the next midnight so the date moves on [REL-03]. */
object WidgetRefresher {
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        runCatching {
            TodayWidget().updateAll(app)
            WeekWidget().updateAll(app)
            MonthWidget().updateAll(app)
        }
        scheduleMidnight(app)
    }

    /** An inexact, non-waking alarm: widgets are only seen with the screen on, so a few minutes late is fine. */
    private fun scheduleMidnight(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5_000
        val pi = PendingIntent.getBroadcast(
            context, REQ_MIDNIGHT,
            Intent(context, MidnightReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.set(AlarmManager.RTC, at, pi)
    }

    private const val REQ_MIDNIGHT = 2001
}

class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val pending = goAsync()
        container.appScope.launch {
            try {
                WidgetRefresher.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
