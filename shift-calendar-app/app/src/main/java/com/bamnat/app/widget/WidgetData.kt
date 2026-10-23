package com.bamnat.app.widget

import android.content.Context
import com.bamnat.app.appContainer
import com.bamnat.app.data.rules
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
import com.bamnat.core.work.WidgetFigures
import com.bamnat.core.work.WorkStyle
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.schedule.CalendarGrid
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
    /** [COMN-05] A part-timer's week against 주휴 15시간. */
    val hourlyWeek: WidgetFigures.HourlyWeek? = null,
    /** [COMN-05] A field worker's week of days or 공수, and what is still owed. */
    val fieldWeek: WidgetFigures.FieldWeek? = null,
    /** [COMN-05] A day worker's 연차 left this year; null when the days granted are not set. */
    val leaveLeft: Double? = null,
    /** [INV-16] An investor's today and each day's result. */
    val invest: InvestLine? = null,
    /** [DAILY-01] The first line of each day's first memo on 내 달력 (today and tomorrow), while 위젯에 메모 보이기 is on. */
    val memos: Map<LocalDate, String> = emptyMap(),
    /** [DAILY-03] Every team's work today, for the 모든 조 widget; null without a rotation to follow. */
    val teams: List<TeamToday>? = null,
    /** [WIDG-01] The next shift start or end after now, when the today and countdown widgets redraw. */
    val nextChange: LocalDateTime? = null,
    /** [WIDG-05] The countdown widget's line; null with nothing booked in the coming week. */
    val countdown: String? = null,
    /** [WIDG-05] I 리뷰 N6(b): the teams that could take my shift today, as 대근 찾기 says; null on a day off. */
    val coverTeams: List<String>? = null,
    /** [WIDG-02] Days with a memo on 내 달력 around this month, while 위젯에 메모 보이기 is on. */
    val memoDays: Set<LocalDate> = emptySet(),
) {
    /** [DAILY-03] One team on the 모든 조 widget: its name, today's and tomorrow's shift (null for a day off). */
    class TeamToday(val name: String, val mine: Boolean, val today: com.bamnat.core.model.ShiftType?, val tomorrow: com.bamnat.core.model.ShiftType?)

    /** [INV-16] What the investor's widgets say: today's result, the total return, each day's result. */
    class InvestLine(val todayPnl: Long, val todayTrades: Int, val totalReturn: Double?, val days: Map<LocalDate, Long>, val hide: Boolean, val empty: Boolean)

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
        CalendarFocus.INVEST -> (invest == null || invest.empty) && !hasPattern
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
                    settings.restCheck, settings.strictRest,
                    rules = com.bamnat.core.schedule.RotationSpeed.of(com.bamnat.core.schedule.PatternTimeline.current(snapshot.patterns, now.toLocalDate()),
                        snapshot.shiftTypes).adjust(settings.rules))
            }
            // [CAL-11] Field and shop work fill the widgets from the money book, read on the phone only.
            val book = if (focus == CalendarFocus.SHIFTS || focus == CalendarFocus.INVEST) null else container.calendars.money(PersonalCalendar.MAIN).book.first()
            val today = now.toLocalDate()
            // [COMN-05] Each kind of work's own line on the today widget.
            val hourly = if (settings.workStyle == WorkStyle.HOURLY) {
                // [HRLY-02] The same week and 법정 휴게 as the 주휴수당 card in 통계.
                val p = container.payStore.profile.value
                val hours = snapshot.copy(legalBreaks = p?.legalBreaks == true).resolver
                WidgetFigures.HourlyWeek(WorkHoursCalculator(hours, p?.holidayWeekFrom ?: java.time.DayOfWeek.MONDAY).week(today).totalMinutes)
            } else null
            val field = if (settings.workStyle == WorkStyle.FIELD && book != null) {
                WidgetFigures.fieldWeek(book.jobs, CalendarGrid.weekStartOf(today, settings.weekStart), today)
            } else null
            // [LEAVE-01] What the 연차 원장 says is usable today; nothing until it has a lot.
            val leaveLeft = if (settings.workStyle == WorkStyle.DAY && container.payStore.profile.value != null) {
                container.leaveRepository.now().takeIf { it.lots.isNotEmpty() }?.available
            } else null
            // [WIDG-01][WIDG-05] Yesterday's night to a week ahead: what is going on now and what comes next.
            val soon = snapshot.resolver.resolveRange(today.minusDays(1), today.plusDays(7))
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
                hourlyWeek = hourly,
                fieldWeek = field,
                leaveLeft = leaveLeft,
                invest = if (focus == CalendarFocus.INVEST) investLine(container, now.toLocalDate()) else null,
                memos = if (settings.widgetMemo) memoLines(container, today) else emptyMap(),
                teams = teamsToday(container, snapshot, today),
                nextChange = com.bamnat.core.schedule.ShiftEvents.nextChange(soon, now),
                countdown = com.bamnat.core.schedule.ShiftEvents.countdown(soon, now),
                coverTeams = coverToday(container, snapshot, today, settings),
                memoDays = if (settings.widgetMemo) memoDays(container, today) else emptySet(),
            )
        }

        /** [WIDG-02] Which days around this month (a year either way, as the month widget turns) have a memo. */
        private suspend fun memoDays(container: com.bamnat.app.AppContainer, today: LocalDate): Set<LocalDate> =
            runCatching { container.memoRepository.datesWithMemos(PersonalCalendar.MAIN).first() }.getOrDefault(emptySet())
                .filterTo(HashSet()) { !it.isBefore(today.minusYears(1)) && !it.isAfter(today.plusYears(1)) }

        /** [WIDG-05] Who could take today's shift of mine, the first names 대근 찾기 lists. */
        private fun coverToday(container: com.bamnat.app.AppContainer, snapshot: com.bamnat.app.data.ScheduleSnapshot, today: LocalDate,
            settings: com.bamnat.app.data.AppSettings): List<String>? {
            val mine = snapshot.resolver.resolve(today).shiftType?.takeIf { !it.isOff } ?: return null
            val rotation = com.bamnat.app.ui.calendar.rotationOf(snapshot, today) ?: return null
            val roster = com.bamnat.app.ui.calendar.rosterFor(container.teams.get(PersonalCalendar.MAIN), rotation)
            return com.bamnat.core.schedule.CoverFinder.find(roster, rotation, snapshot.typesById, today, mine.id)
                .candidates.map { it.team.name }
        }

        /** [DAILY-01] Today's and tomorrow's first memo line on 내 달력, read on the phone only. */
        private suspend fun memoLines(container: com.bamnat.app.AppContainer, today: LocalDate): Map<LocalDate, String> =
            listOf(today, today.plusDays(1)).mapNotNull { d ->
                runCatching { container.memoRepository.memosOn(d, PersonalCalendar.MAIN).first() }.getOrNull()
                    ?.let { memoLine(it) }?.let { d to it }
            }.toMap()

        /** [DAILY-01] The line a widget shows for a day's memos: the pinned or first one's title, else its first line. */
        fun memoLine(memos: List<com.bamnat.core.memo.Memo>): String? {
            val shown = memos.filter { !it.inTrash && !it.isBlank }
                .sortedWith(compareByDescending<com.bamnat.core.memo.Memo> { it.pinned }.thenBy { it.createdAtMillis })
            val line = shown.firstOrNull()?.let { m -> m.title.ifBlank { m.preview }.lineSequence().firstOrNull()?.trim()?.take(MEMO_CHARS) }
                ?.takeIf { it.isNotEmpty() } ?: return null
            return if (shown.size > 1) "$line 외 ${shown.size - 1}" else line
        }

        private const val MEMO_CHARS = 30

        /** [DAILY-03] 내 달력's teams today and tomorrow, as 모든 조 보기 shows them. */
        private fun teamsToday(container: com.bamnat.app.AppContainer, snapshot: com.bamnat.app.data.ScheduleSnapshot, today: LocalDate): List<TeamToday>? {
            val rotation = com.bamnat.app.ui.calendar.rotationOf(snapshot, today) ?: return null
            val roster = com.bamnat.app.ui.calendar.rosterFor(container.teams.get(PersonalCalendar.MAIN), rotation)
            return teamLines(roster, rotation, snapshot.typesById, today)
        }

        /** [DAILY-03] Each team's shift today and tomorrow; a day off is null. */
        fun teamLines(roster: com.bamnat.core.schedule.TeamRoster, rotation: com.bamnat.core.model.WorkPattern.Rotation,
            types: Map<Long, com.bamnat.core.model.ShiftType>, today: LocalDate): List<TeamToday> =
            roster.teams.map { t ->
                TeamToday(t.name, t.mine,
                    roster.shiftOn(rotation, t, today)?.let { types[it] }?.takeIf { !it.isOff },
                    roster.shiftOn(rotation, t, today.plusDays(1))?.let { types[it] }?.takeIf { !it.isOff })
            }

        /** [INV-16] The main calendar's investor records, read on the phone only. */
        private suspend fun investLine(container: com.bamnat.app.AppContainer, today: LocalDate): InvestLine {
            val book = com.bamnat.app.data.InvestRepository.of(container.database).now()
            val result = com.bamnat.core.invest.Portfolio.build(book)
            val days = com.bamnat.core.invest.Portfolio.dailyPnl(result, book.cash)
            return InvestLine(days[today] ?: 0L, book.trades.count { it.date == today }, result.summary.simpleReturn, days,
                book.settings.hideAmounts, book.isEmpty)
        }
    }
}
