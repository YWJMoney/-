package com.bamnat.core.schedule

import com.bamnat.core.holiday.KoreanHolidays
import java.time.LocalDate
import java.time.YearMonth

/**
 * [CAL-07] Public holidays a weekly pattern turns into working days. Only days the pattern decides
 * and the user never touched are named: a holiday the user already changed, or worked on purpose
 * with extra work or leave on it, is theirs.
 */
object HolidayWork {
    fun patternDays(resolver: ScheduleResolver, month: YearMonth): List<LocalDate> =
        resolver.resolveRange(month.atDay(1), month.atEndOfMonth())
            .filter { d -> d.source == DaySource.PATTERN && d.isWorkDay && d.entry == null && KoreanHolidays.isHoliday(d.date) }
            .map { it.date }
}

/** [CAL-09] Every date from [a] to [b], both included, in either order: the days a range stamp covers. */
fun datesBetween(a: LocalDate, b: LocalDate): List<LocalDate> {
    val (from, to) = if (a.isAfter(b)) b to a else a to b
    return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
}
