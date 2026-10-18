package com.bamnat.app.widget

import android.content.Context
import com.bamnat.app.appContainer
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.alarm.PlannedAlarm
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.sleep.SleepMerge
import com.bamnat.core.sleep.TodayFlow
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
    /** [SLP-14] 오늘의 흐름, while the user keeps it on: its short line goes on the today widget. */
    val flow: TodayFlow.Card? = null,
) {
    fun day(date: LocalDate): ResolvedDay = resolver.resolve(date)

    companion object {
        suspend fun load(context: Context): WidgetData {
            val container = context.appContainer
            val snapshot = container.scheduleRepository.snapshot.first()
            val settings = container.settingsRepository.current
            val now = LocalDateTime.now()
            val flow = if (!settings.sleepTips || settings.workStyle?.usesPayEngine == false || snapshot.isEmpty) null else {
                val log = SleepMerge.merge(container.sleepRepository.recent(days = 2).first(), container.healthSleep.sleeps.value)
                TodayFlow.of(now, snapshot.resolver, settings.alarmsEnabled, log, settings.sleepTargetMinutes, settings.commuteMinutes,
                    settings.restCheck, settings.strictRest)
            }
            return WidgetData(
                today = now.toLocalDate(),
                weekStart = settings.weekStart,
                resolver = snapshot.resolver,
                hasPattern = !snapshot.isEmpty,
                nextAlarm = if (settings.alarmsEnabled) AlarmPlanner.nextAlarm(snapshot.resolver, now) else null,
                flow = flow,
            )
        }
    }
}
