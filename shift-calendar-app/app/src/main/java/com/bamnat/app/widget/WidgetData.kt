package com.bamnat.app.widget

import android.content.Context
import com.bamnat.app.appContainer
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.alarm.PlannedAlarm
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.sleep.SleepMerge
import com.bamnat.core.sleep.TodayFlow
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** What every widget draws, read once per update. */
class WidgetData(
    val today: LocalDate,
    val weekStart: DayOfWeek,
    val resolver: ScheduleResolver,
    val hasPattern: Boolean,
    val nextAlarm: PlannedAlarm?,
    /** [SLP-14] 오늘의 흐름, while the user keeps it on: its short line goes on the today widget. */
    val flow: TodayFlow.Card? = null,
    /** [CAL-11] What the user's calendar is about, so each widget speaks of shifts, jobs or sales. */
    val focus: CalendarFocus = CalendarFocus.SHIFTS,
    val style: WorkStyle? = null,
    /** [CAL-11] Booked jobs, by title and time only: a home screen is seen by others, so no amounts. */
    val jobs: List<Job> = emptyList(),
    val sales: Map<LocalDate, DailySales> = emptyMap(),
) {
    fun day(date: LocalDate): ResolvedDay = resolver.resolve(date)

    /** [CAL-11] Jobs running on [date], earliest first. */
    fun jobsOn(date: LocalDate): List<Job> = jobs.filter { it.covers(date) }.sortedWith(compareBy({ it.start }, { it.date }))

    /** [CAL-11] The first job after [date]. */
    fun nextJobAfter(date: LocalDate): Job? = jobs.filter { it.date.isAfter(date) }.minWithOrNull(compareBy({ it.date }, { it.start }))

    /** [CAL-11] The month's sales so far. */
    fun monthSales(month: YearMonth): Long = sales.values.filter { YearMonth.from(it.date) == month }.sumOf { it.sales }

    /** [CAL-11] Nothing yet for this kind of work to show. */
    val isEmpty: Boolean get() = when (focus) {
        CalendarFocus.SHIFTS -> !hasPattern
        CalendarFocus.JOBS -> jobs.isEmpty() && !hasPattern
        CalendarFocus.SALES -> sales.isEmpty() && !hasPattern
    }

    companion object {
        suspend fun load(context: Context): WidgetData {
            val container = context.appContainer
            val snapshot = container.scheduleRepository.snapshot.first()
            val settings = container.settingsRepository.current
            val now = LocalDateTime.now()
            val focus = CalendarFocus.of(settings.workStyle)
            val flow = if (!settings.sleepTips || settings.workStyle?.usesPayEngine == false || snapshot.isEmpty) null else {
                val log = SleepMerge.merge(container.sleepRepository.recent(days = 2).first(), container.healthSleep.sleeps.value)
                TodayFlow.of(now, snapshot.resolver, settings.alarmsEnabled, log, settings.sleepTargetMinutes, settings.commuteMinutes,
                    settings.restCheck, settings.strictRest)
            }
            // [CAL-11] Field and shop work fill the widgets from the money book, read on the phone only.
            val book = if (focus == CalendarFocus.SHIFTS) null else container.calendars.money(PersonalCalendar.MAIN).book.first()
            return WidgetData(
                today = now.toLocalDate(),
                weekStart = settings.weekStart,
                resolver = snapshot.resolver,
                hasPattern = !snapshot.isEmpty,
                nextAlarm = if (settings.alarmsEnabled) AlarmPlanner.nextAlarm(snapshot.resolver, now) else null,
                flow = flow,
                focus = focus,
                style = settings.workStyle,
                jobs = book?.jobs.orEmpty(),
                sales = book?.sales.orEmpty().filter { it.sales != 0L || it.costs != 0L }.associateBy { it.date },
            )
        }
    }
}
