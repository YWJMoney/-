package com.bamnat.core.work

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.ScheduleResolver
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** [CAL-08] One line of the month as a list: a week's header, a day, or a run of empty days. */
sealed interface AgendaRow {
    val key: String

    /** The week's dates within the month and its total in the kind of work's own measure. */
    data class Week(
        val start: LocalDate,
        val end: LocalDate,
        val minutes: Int,
        val jobs: Int,
        val jobEarned: Long,
        val sales: Long,
    ) : AgendaRow {
        override val key: String get() = "w$start"
    }

    data class Day(
        val day: ResolvedDay,
        /** Jobs running this day; a job over several days is on each of them. */
        val jobs: List<Job>,
        val sales: DailySales?,
        val memos: Int,
        val holiday: String?,
    ) : AgendaRow {
        val date: LocalDate get() = day.date
        override val key: String get() = "d$date"
    }

    /** Days in a row with nothing on them, folded into one line. */
    data class Gap(val from: LocalDate, val to: LocalDate) : AgendaRow {
        val days: Int get() = (to.toEpochDay() - from.toEpochDay()).toInt() + 1
        override val key: String get() = "g$from"
    }
}

object MonthAgenda {
    fun of(
        month: YearMonth,
        focus: CalendarFocus,
        resolver: ScheduleResolver,
        jobs: List<Job> = emptyList(),
        sales: List<DailySales> = emptyList(),
        memoCounts: Map<LocalDate, Int> = emptyMap(),
        weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    ): List<AgendaRow> {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val running = jobs.filter { !it.lastDate.isBefore(first) && !it.date.isAfter(last) }.sortedWith(compareBy({ it.start }, { it.date }))
        val salesByDate = sales.filter { YearMonth.from(it.date) == month }.associateBy { it.date }
        val rows = mutableListOf<AgendaRow>()
        var weekFrom = first
        while (!weekFrom.isAfter(last)) {
            val weekEnd = minOf(weekFrom.with(TemporalAdjusters.previousOrSame(weekStart)).plusDays(6), last)
            val days = generateSequence(weekFrom) { it.plusDays(1) }.takeWhile { !it.isAfter(weekEnd) }.map { d ->
                AgendaRow.Day(resolver.resolve(d), running.filter { it.covers(d) }, salesByDate[d], memoCounts[d] ?: 0, KoreanHolidays.nameOf(d))
            }.toList()
            val started = running.filter { !it.date.isBefore(weekFrom) && !it.date.isAfter(weekEnd) }
            rows += AgendaRow.Week(
                start = weekFrom,
                end = weekEnd,
                minutes = days.sumOf { it.day.totalMinutes },
                // [FLD-03] A cancelled job is not work; it adds only the deposit it kept.
                jobs = started.count { !it.cancelled },
                jobEarned = started.sumOf { it.earned },
                sales = days.sumOf { it.sales?.sales ?: 0 },
            )
            var gapFrom: LocalDate? = null
            for (d in days) {
                if (isEmpty(d, focus)) {
                    if (gapFrom == null) gapFrom = d.date
                    continue
                }
                gapFrom?.let { rows += AgendaRow.Gap(it, d.date.minusDays(1)) }
                gapFrom = null
                rows += d
            }
            gapFrom?.let { rows += AgendaRow.Gap(it, weekEnd) }
            weekFrom = weekEnd.plusDays(1)
        }
        return rows
    }

    /**
     * Nothing to say about the day: no work, no record, no memo, not a holiday. A day off on the
     * roster (휴무) is something to a shift worker; to a shop or field worker it is just empty.
     */
    fun isEmpty(row: AgendaRow.Day, focus: CalendarFocus): Boolean {
        val d = row.day
        val shift = d.shiftType?.let { focus == CalendarFocus.SHIFTS || !it.isOff } ?: false
        return !shift && !d.hasCustomTimes && d.extras.isEmpty() && d.leave == null && row.jobs.isEmpty() &&
            (row.sales == null || (row.sales.sales == 0L && row.sales.costs == 0L)) && row.memos == 0 && row.holiday == null
    }
}
