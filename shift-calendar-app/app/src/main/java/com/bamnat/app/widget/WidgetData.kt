package com.bamnat.app.widget

import android.content.Context
import com.bamnat.app.appContainer
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.alarm.PlannedAlarm
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.ScheduleResolver
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** What every widget draws, read once per update. */
class WidgetData(
    val today: LocalDate,
    val weekStart: DayOfWeek,
    val resolver: ScheduleResolver,
    val hasPattern: Boolean,
    val nextAlarm: PlannedAlarm?,
) {
    fun day(date: LocalDate): ResolvedDay = resolver.resolve(date)

    companion object {
        suspend fun load(context: Context): WidgetData {
            val container = context.appContainer
            val snapshot = container.scheduleRepository.snapshot.first()
            val settings = container.settingsRepository.current
            val now = LocalDateTime.now()
            return WidgetData(
                today = now.toLocalDate(),
                weekStart = settings.weekStart,
                resolver = snapshot.resolver,
                hasPattern = !snapshot.isEmpty,
                nextAlarm = if (settings.alarmsEnabled) AlarmPlanner.nextAlarm(snapshot.resolver, now) else null,
            )
        }
    }
}
