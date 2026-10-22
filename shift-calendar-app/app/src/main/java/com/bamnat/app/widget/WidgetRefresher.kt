package com.bamnat.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.bamnat.app.appContainer
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Redraws every widget and keeps a wake-up for the next midnight so the date moves on [REL-03], or
 * sooner when the line of 오늘의 흐름 changes before then [SLP-14].
 */
object WidgetRefresher {
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        // [FEAT-18] The lock-screen 오늘 근무 notice moves on with the same wake-up as the widgets.
        val notice = runCatching { com.bamnat.app.alarm.TodayShiftNotice.update(app) }.getOrDefault(false)
        // [STAB-01] Every edit asks for this; with no widget on the home screen there is nothing to draw or wake for.
        val placed = runCatching {
            val manager = GlanceAppWidgetManager(app)
            listOf(TodayWidget::class.java, WeekWidget::class.java, MonthWidget::class.java, TeamsWidget::class.java).any { manager.getGlanceIds(it).isNotEmpty() }
        }.getOrDefault(true)
        if (!placed) {
            if (notice) scheduleNext(app, null)
            return
        }
        runCatching {
            TodayWidget().updateAll(app)
            WeekWidget().updateAll(app)
            MonthWidget().updateAll(app)
            TeamsWidget().updateAll(app)
        }
        val flowChanges = runCatching { WidgetData.load(app).flow?.changesAt }.getOrNull()
        scheduleNext(app, flowChanges)
    }

    /** An inexact, non-waking alarm: widgets are only seen with the screen on, so a few minutes late is fine. */
    private fun scheduleNext(context: Context, flowChanges: LocalDateTime?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5_000
        val at = flowChanges?.atZone(zone)?.toInstant()?.toEpochMilli()?.plus(5_000)?.coerceAtMost(midnight) ?: midnight
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
