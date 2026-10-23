package com.bamnat.core.hours

import com.bamnat.core.schedule.ScheduleResolver
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
    /** [EXTRA-02] 주 52시간 (제53조: 40시간 + 연장 12시간); a week above it is pointed out, softer than [weeklyLimitHours]. */
    val legalWeeklyHours: Int = 52,
)

data class WeekSummary(
    val start: LocalDate,
    val end: LocalDate,
    val totalMinutes: Int,
    /** Hours on the roster. */
    val scheduledMinutes: Int,
    /** [EXTRA-01] Extra work: 잔업, 특근, 대근 … on working days and days off. */
    val extraMinutes: Int,
    /** The part of [extraMinutes] worked on days off. */
    val restDayExtraMinutes: Int,
    val workDays: Int,
    val overLimit: Boolean,
    /** [EXTRA-02] Minutes past 주 52시간 (제53조), 0 when within. */
    val overLegalMinutes: Int = 0,
) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

data class MonthSummary(
    val month: YearMonth,
    val totalMinutes: Int,
    val scheduledMinutes: Int,
    val extraMinutes: Int,
    val restDayExtraMinutes: Int,
    val workDays: Int,
    /** Days off with extra work on them. */
    val restDayWorkDays: Int,
    /** [EXTRA-01] Minutes and pieces of extra work by kind id. */
    val extraByKind: Map<Long, Int> = emptyMap(),
    val extraCountByKind: Map<Long, Int> = emptyMap(),
    /** Every week that touches this month, each counted over its full seven days. */
    val weeks: List<WeekSummary>,
) {
    val weeksOverLimit: List<WeekSummary> get() = weeks.filter { it.overLimit }
    val weeksOverLegal: List<WeekSummary> get() = weeks.filter { it.overLegalMinutes > 0 }
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
 * from Sunday 22:00 to Monday 07:00 belongs to Sunday's week. Extra work on working days and
 * days off is always included.
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
            extraMinutes = days.sumOf { it.extraMinutes },
            restDayExtraMinutes = days.filter { it.isRestDay }.sumOf { it.extraMinutes },
            workDays = days.count { it.totalMinutes > 0 },
            overLimit = total > limits.weeklyLimitHours * 60,
            overLegalMinutes = (total - limits.legalWeeklyHours * 60).coerceAtLeast(0),
        )
    }

    /**
     * [CAL-32] I 리뷰 P9: the week's hours with each night shift split at midnight, the way some payrolls count
     * it. [week] puts a 22:00~07:00 shift wholly on the day it starts, so a Sunday night lands in this week; here
     * the part after midnight goes to the next day (the break shared in proportion). Equal to [week]'s total
     * when no shift crosses the week's edges.
     */
    fun weekSplitAtMidnight(containing: LocalDate): Int {
        val w = week(containing)
        return w.totalMinutes - afterMidnight(resolver.resolve(w.end)) + afterMidnight(resolver.resolve(w.start.minusDays(1)))
    }

    /** The rostered minutes of [day] that fall after midnight, 0 when the shift ends the same day. */
    private fun afterMidnight(day: com.bamnat.core.schedule.ResolvedDay): Int {
        val s = day.start ?: return 0
        val e = day.end ?: return 0
        if (day.scheduledMinutes <= 0 || e.isAfter(s)) return 0
        val span = com.bamnat.core.model.WorkTime.spanMinutes(s, e)
        val after = e.toSecondOfDay() / 60
        return Math.round(day.scheduledMinutes.toDouble() * after / span).toInt()
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
            extraMinutes = days.sumOf { it.extraMinutes },
            restDayExtraMinutes = days.filter { it.isRestDay }.sumOf { it.extraMinutes },
            workDays = days.count { it.totalMinutes > 0 },
            restDayWorkDays = days.count { it.isRestDay && it.extraMinutes > 0 },
            extraByKind = days.flatMap { it.extras }.groupBy { it.kind.id }.mapValues { (_, l) -> l.sumOf { it.minutes } },
            extraCountByKind = days.flatMap { it.extras }.groupingBy { it.kind.id }.eachCount(),
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

    /** Hours for tiles and charts without a trailing .0: "73.5", "290" [VIS-01]. */
    fun hours(minutes: Double): String = decimalHours(minutes).removeSuffix(".0")
}
