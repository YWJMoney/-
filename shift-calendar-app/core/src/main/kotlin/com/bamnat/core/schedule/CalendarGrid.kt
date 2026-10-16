package com.bamnat.core.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Dates laid out as calendar weeks, shared by the month widget and anything else that draws a grid. */
object CalendarGrid {
    /** The first day of the week containing [date]. */
    fun weekStartOf(date: LocalDate, weekStart: DayOfWeek): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(weekStart))

    /** Whole weeks covering [month] (4 to 6 of them), each 7 dates starting on [weekStart]. */
    fun monthWeeks(month: YearMonth, weekStart: DayOfWeek): List<List<LocalDate>> {
        val first = weekStartOf(month.atDay(1), weekStart)
        val last = month.atEndOfMonth()
        return generateSequence(first) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(last) }
            .map { start -> (0L until 7L).map { start.plusDays(it) } }
            .toList()
    }

    /** The 7 days starting at [from]. */
    fun week(from: LocalDate): List<LocalDate> = (0L until 7L).map { from.plusDays(it) }
}
