package com.bamnat.core.work

import com.bamnat.core.model.NightShift
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.RestCheck
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth

/**
 * [SHFT-07] One month of a shift worker's year as far as the body feels it: days worked, night shifts and
 * their share, the longest run of nights, quick returns (less than 11 hours off between two pieces of work,
 * see [RestCheck.MIN_REST_MINUTES]) and work on weekends and 공휴일. Numbers only, no diagnosis.
 */
data class HealthMonth(
    val month: YearMonth,
    val workDays: Int,
    val nights: Int,
    /** Longest run of nights in a row that ends in this month (a run from the month before counts whole). */
    val longestNightRun: Int,
    val quickReturns: Int,
    val weekendDays: Int,
    val holidayDays: Int,
) {
    /** Nights out of the days worked, 0..1. */
    val nightShare: Double get() = if (workDays == 0) 0.0 else nights.toDouble() / workDays
}

/** [SHFT-07] A year of [HealthMonth]s with the year's totals; months after [until] are left out. */
data class HealthYear(val year: Int, val months: List<HealthMonth>) {
    val workDays: Int get() = months.sumOf { it.workDays }
    val nights: Int get() = months.sumOf { it.nights }
    val nightShare: Double get() = if (workDays == 0) 0.0 else nights.toDouble() / workDays
    val longestNightRun: Int get() = months.maxOfOrNull { it.longestNightRun } ?: 0
    val quickReturns: Int get() = months.sumOf { it.quickReturns }
    val weekendDays: Int get() = months.sumOf { it.weekendDays }
    val holidayDays: Int get() = months.sumOf { it.holidayDays }
    /** The month with the most nights, if any month had one. */
    val busiestNightMonth: HealthMonth? get() = months.filter { it.nights > 0 }.maxByOrNull { it.nights }
    val isEmpty: Boolean get() = workDays == 0
}

object ShiftHealth {
    /**
     * [SHFT-07] [year] read off the calendar [resolve], up to and including [until] (the whole year when it
     * is later). [holidayName] names a 공휴일 (KoreanHolidays::nameOf in the app).
     */
    fun year(year: Int, resolve: (LocalDate) -> ResolvedDay, holidayName: (LocalDate) -> String?, until: LocalDate = LocalDate.of(year, 12, 31)): HealthYear {
        val first = LocalDate.of(year, 1, 1)
        val last = minOf(until, LocalDate.of(year, 12, 31))
        if (last.isBefore(first)) return HealthYear(year, emptyList())
        // A week before the year, so a run of nights or a short rest across New Year counts right.
        val days = generateSequence(first.minusDays(7)) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map(resolve).toList()
        val worked = days.filter(::worked).map { it.date }.toSet()
        val nights = days.filter(::night).map { it.date }.toSet()

        // Each night's run length so far, so a run is counted in the month it ends with its whole length.
        val runEnding = HashMap<LocalDate, Int>()
        for (d in nights.sorted()) runEnding[d] = (runEnding[d.minusDays(1)] ?: 0) + 1

        val blocks = days.flatMap { RestCheck.blocks(it) }.sortedBy { it.from }
        val quick = blocks.zipWithNext().mapNotNull { (a, b) ->
            val gap = Duration.between(a.to, b.from).toMinutes()
            b.from.toLocalDate().takeIf { gap in 0 until RestCheck.MIN_REST_MINUTES }
        }

        val months = (1..last.monthValue).map { m ->
            val ym = YearMonth.of(year, m)
            fun inMonth(d: LocalDate) = YearMonth.from(d) == ym
            val monthWorked = worked.filter(::inMonth)
            HealthMonth(
                month = ym,
                workDays = monthWorked.size,
                nights = nights.count(::inMonth),
                longestNightRun = runEnding.filterKeys { inMonth(it) && (it.plusDays(1) !in nights || !inMonth(it.plusDays(1))) }
                    .values.maxOrNull() ?: 0,
                quickReturns = quick.count(::inMonth),
                weekendDays = monthWorked.count { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY },
                holidayDays = monthWorked.count { holidayName(it) != null },
            )
        }
        return HealthYear(year, months)
    }

    /** A day with work on it: the day's own shift (not taken as a whole day of leave) or extra work. */
    fun worked(day: ResolvedDay): Boolean =
        (day.isWorkDay && day.leave?.isFullDay != true) || day.extras.any { it.minutes > 0 }

    /** A day whose work reaches into 22:00~06:00: its own shift, or extra work on the clock such as a 야간 대근. */
    fun night(day: ResolvedDay): Boolean {
        val own = day.isWorkDay && day.leave?.isFullDay != true && day.start != null && day.end != null && NightShift.crosses(day.start, day.end)
        return own || day.extras.any { e -> e.start != null && e.end != null && NightShift.crosses(e.start!!, e.end!!) }
    }
}
