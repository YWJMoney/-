package com.bamnat.core.work

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.schedule.ScheduleResolver
import java.time.LocalDate
import java.time.YearMonth

/**
 * [CAL-06] One month at a glance, as the bar over the calendar says it for each kind of work:
 * days and hours for shifts, jobs and the money they bring for field work, sales for a shop. The
 * pay estimate comes from the app, which owns the pay calculator.
 */
data class MonthOverview(
    val month: YearMonth,
    val focus: CalendarFocus,
    /** Days with any work: a shift or extra work. */
    val workDays: Int = 0,
    /** Shift and extra work minutes, as the hours line counts them. */
    val workMinutes: Int = 0,
    val extraMinutes: Int = 0,
    /** Leave taken, in days (반차 is half). */
    val leaveDays: Double = 0.0,
    /** Working days on a public holiday. */
    val holidayWorkDays: Int = 0,
    /** Jobs starting this month. */
    val jobs: Int = 0,
    /** Days of this month a job runs on. */
    val jobDays: Int = 0,
    /** What this month's jobs pay (after 3.3% when withheld). */
    val jobEarned: Long = 0,
    val jobReceived: Long = 0,
    val sales: Long = 0,
    val salesCosts: Long = 0,
    /** Days the shop wrote sales on. */
    val openDays: Int = 0,
    val bestDay: LocalDate? = null,
    val bestSales: Long = 0,
) {
    /** This month's jobs not yet paid. */
    val jobOwed: Long get() = (jobEarned - jobReceived).coerceAtLeast(0)
    val salesNet: Long get() = sales - salesCosts
    /** Average sales on a day the shop wrote down. */
    val salesPerDay: Long get() = if (openDays == 0) 0 else sales / openDays
    /** Nothing at all to show for the month. */
    val isEmpty: Boolean get() = when (focus) {
        CalendarFocus.SHIFTS -> workDays == 0 && leaveDays == 0.0
        CalendarFocus.JOBS -> jobs == 0 && jobDays == 0 && workDays == 0
        CalendarFocus.SALES -> openDays == 0 && sales == 0L && salesCosts == 0L
    }

    companion object {
        fun of(
            month: YearMonth,
            focus: CalendarFocus,
            resolver: ScheduleResolver,
            jobs: List<Job> = emptyList(),
            sales: List<DailySales> = emptyList(),
        ): MonthOverview {
            val first = month.atDay(1)
            val last = month.atEndOfMonth()
            val days = resolver.resolveRange(first, last)
            val working = days.filter { it.totalMinutes > 0 }
            val leave = days.sumOf { d -> d.leave?.let { it.quarters / 4.0 } ?: 0.0 }
            val started = jobs.filter { YearMonth.from(it.date) == month }
            val running = jobs.filter { !it.lastDate.isBefore(first) && !it.date.isAfter(last) }
            val jobDays = running.flatMap { j ->
                generateSequence(maxOf(j.date, first)) { it.plusDays(1) }.takeWhile { !it.isAfter(minOf(j.lastDate, last)) }.toList()
            }.toSet().size
            val shop = sales.filter { YearMonth.from(it.date) == month }
            val open = shop.filter { it.sales != 0L || it.costs != 0L }
            val best = open.maxByOrNull { it.sales }?.takeIf { it.sales > 0 }
            return MonthOverview(
                month = month,
                focus = focus,
                workDays = working.size,
                workMinutes = working.sumOf { it.totalMinutes },
                extraMinutes = days.sumOf { it.extraMinutes },
                leaveDays = leave,
                holidayWorkDays = working.count { KoreanHolidays.isHoliday(it.date) },
                jobs = started.size,
                jobDays = jobDays,
                jobEarned = started.sumOf { it.payable },
                jobReceived = started.sumOf { it.received },
                sales = shop.sumOf { it.sales },
                salesCosts = shop.sumOf { it.costs },
                openDays = open.size,
                bestDay = best?.date,
                bestSales = best?.sales ?: 0,
            )
        }
    }
}
