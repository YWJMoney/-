package com.shiftmate.core.hours

import com.shiftmate.core.schedule.ScheduleResolver
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Legal-limit thresholds, in hours. Defaults follow the Korean 52h / 64h rules. */
data class HourLimits(
    /** A single week above this is flagged (주 64시간). */
    val weeklyLimitHours: Int = 64,
    /** The weekly average over three months above this is flagged (3개월 평균 주 52시간). */
    val threeMonthAverageLimitHours: Int = 52,
)

data class WeekSummary(
    val start: LocalDate,
    val end: LocalDate,
    val totalMinutes: Int,
    val scheduledMinutes: Int,
    val overtimeMinutes: Int,
    val holidayWorkMinutes: Int,
    val workDays: Int,
    val overLimit: Boolean,
) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

data class MonthSummary(
    val month: YearMonth,
    val totalMinutes: Int,
    val scheduledMinutes: Int,
    val overtimeMinutes: Int,
    val holidayWorkMinutes: Int,
    val workDays: Int,
    val holidayWorkDays: Int,
    /** Every week that touches this month, each counted over its full seven days. */
    val weeks: List<WeekSummary>,
) {
    val weeksOverLimit: List<WeekSummary> get() = weeks.filter { it.overLimit }
}

data class ThreeMonthSummary(
    val firstMonth: YearMonth,
    val lastMonth: YearMonth,
    val start: LocalDate,
    val end: LocalDate,
    val totalMinutes: Int,
    val days: Int,
    /** Total minutes divided by the number of weeks in the window (days / 7). */
    val averageWeeklyMinutes: Double,
    /** Total minutes divided by three. */
    val averageMonthlyMinutes: Double,
    val overLimit: Boolean,
)

/**
 * Week, month and three-month totals.
 *
 * Every minute of a shift is counted on the date the shift starts, so an overnight shift
 * from Sunday 22:00 to Monday 07:00 belongs to Sunday's week. Overtime and work on days
 * off are always included.
 */
class WorkHoursCalculator(
    private val resolver: ScheduleResolver,
    private val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    private val limits: HourLimits = HourLimits(),
) {
    fun weekStartOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(weekStart))

    fun week(containing: LocalDate): WeekSummary {
        val start = weekStartOf(containing)
        val end = start.plusDays(6)
        val days = resolver.resolveRange(start, end)
        val total = days.sumOf { it.totalMinutes }
        return WeekSummary(
            start = start,
            end = end,
            totalMinutes = total,
            scheduledMinutes = days.sumOf { it.scheduledMinutes },
            overtimeMinutes = days.sumOf { it.overtimeMinutes },
            holidayWorkMinutes = days.sumOf { it.holidayWorkMinutes },
            workDays = days.count { it.totalMinutes > 0 },
            overLimit = total > limits.weeklyLimitHours * 60,
        )
    }

    fun month(month: YearMonth): MonthSummary {
        val days = resolver.resolveRange(month.atDay(1), month.atEndOfMonth())
        val weeks = generateSequence(weekStartOf(month.atDay(1))) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(month.atEndOfMonth()) }
            .map(::week)
            .toList()
        return MonthSummary(
            month = month,
            totalMinutes = days.sumOf { it.totalMinutes },
            scheduledMinutes = days.sumOf { it.scheduledMinutes },
            overtimeMinutes = days.sumOf { it.overtimeMinutes },
            holidayWorkMinutes = days.sumOf { it.holidayWorkMinutes },
            workDays = days.count { it.totalMinutes > 0 },
            holidayWorkDays = days.count { it.isHolidayWork },
            weeks = weeks,
        )
    }

    /** The three calendar months ending with [lastMonth], e.g. Aug–Oct for October. */
    fun threeMonths(lastMonth: YearMonth): ThreeMonthSummary {
        val firstMonth = lastMonth.minusMonths(2)
        val start = firstMonth.atDay(1)
        val end = lastMonth.atEndOfMonth()
        val total = resolver.resolveRange(start, end).sumOf { it.totalMinutes }
        val days = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
        val avgWeekly = total / (days / 7.0)
        return ThreeMonthSummary(
            firstMonth = firstMonth,
            lastMonth = lastMonth,
            start = start,
            end = end,
            totalMinutes = total,
            days = days,
            averageWeeklyMinutes = avgWeekly,
            averageMonthlyMinutes = total / 3.0,
            overLimit = avgWeekly > limits.threeMonthAverageLimitHours * 60.0,
        )
    }
}

/** "41시간 30분" style formatting shared by the UI. */
object HoursFormat {
    fun korean(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 && m == 0 -> "0시간"
            m == 0 -> "${h}시간"
            h == 0 -> "${m}분"
            else -> "${h}시간 ${m}분"
        }
    }

    /** Compact "8h" / "7.5h" for calendar cells. */
    fun short(minutes: Int): String {
        if (minutes % 60 == 0) return "${minutes / 60}h"
        val tenths = Math.round(minutes / 6.0) // minutes / 60 * 10
        return "${tenths / 10}.${tenths % 10}h"
    }

    /** One decimal place, e.g. 52.3 */
    fun decimalHours(minutes: Double): String {
        val tenths = Math.round(minutes / 6.0)
        return "${tenths / 10}.${tenths % 10}"
    }
}
