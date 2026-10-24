package com.bamnat.core.hours

import com.bamnat.core.schedule.ScheduleResolver
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * [PAYX-29] 주 52시간의 예외 (근로기준법 법률 제21533호 현행본, research/1.23-K-원문.md 11장). The limit a single week is
 * held to, counting 연장 12시간 (제53조 제1항): [weeklyHours] null when the law sets none for one week. [averageHours] is
 * the average over the settlement period for 선택적 근로시간제. The app never decides whether the company's 합의 or 인가
 * is in place; it only shows the limit of the type the user picked.
 */
enum class HoursException(val label: String, val weeklyHours: Int?, val averageHours: Int?, val note: String) {
    NONE("없음 (주 52시간)", 52, null, "1주 40시간 + 연장 12시간까지예요 (근로기준법 제50조·제53조 제1항)"),
    FLEX_2W("탄력적 근로시간제 2주", 60, null,
        "한 주 소정근로 48시간 + 연장 12시간까지예요 (제51조 제1항, 제53조 제2항)"),
    FLEX_3M("탄력적 근로시간제 3개월·6개월", 64, null,
        "한 주 52시간·하루 12시간 + 연장 12시간까지예요. 근로자대표와 서면 합의가 있어야 해요 (제51조 제2항, 제51조의2)"),
    SELECTIVE("선택적 근로시간제", null, 52,
        "정산기간을 평균해 주 40시간 + 연장 12시간까지예요. 한 주씩은 한도가 없어요 (제52조, 제53조 제2항)"),
    SPECIAL_EXTENSION("특별연장근로 (인가)", 64, null,
        "고용노동부 인가를 받은 기간에만 주 12시간을 넘겨 연장할 수 있어요. 늘릴 수 있는 시간은 인가마다 달라요 (제53조 제4항, 확인 필요)"),
    SPECIAL_INDUSTRY("근로시간 특례 업종", null, null,
        "육상·수상·항공 운송, 기타 운송서비스, 보건업은 서면 합의로 주 52시간을 넘을 수 있어요. 대신 근무가 끝나면 다음 근무까지 11시간 이상 쉬어야 해요 (제59조)"),
    SUPERVISORY("감시·단속 (승인)", null, null,
        "고용노동부 승인을 받은 감시·단속 근로자는 근로시간 한도가 적용되지 않아요 (제63조 제3호)"),
    ;

    companion object {
        /** [SPECIAL_INDUSTRY]: 근로일 종료 후 다음 근로일 개시 전까지 연속 11시간 이상의 휴식 (제59조 제2항). */
        const val REST_MINUTES = 11 * 60

        fun of(name: String?): HoursException = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Legal-limit thresholds, in hours. Defaults follow the Korean 52h / 64h rules. */
data class HourLimits(
    /** A single week above this is flagged (주 64시간). */
    val weeklyLimitHours: Int = 64,
    /** The weekly average over three months above this is flagged (3개월 평균 주 52시간). */
    val threeMonthAverageLimitHours: Int = 52,
    /** [EXTRA-02] 주 52시간 (제53조: 40시간 + 연장 12시간); a week above it is pointed out, softer than [weeklyLimitHours]. */
    val legalWeeklyHours: Int = 52,
    /** [PAYX-29] The 52시간 exception the company uses; its own limit replaces [legalWeeklyHours]. */
    val exception: HoursException = HoursException.NONE,
) {
    /** [PAYX-29] The week's legal limit in minutes, or null when the exception sets none. */
    val legalWeeklyMinutes: Int?
        get() = if (exception == HoursException.NONE) legalWeeklyHours * 60 else exception.weeklyHours?.let { it * 60 }
}

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

/** [CAL-34][CAL-40] See [WorkHoursCalculator.glance]. */
data class WeekGlance(val start: LocalDate, val minutes: Int, val nights: Int, val leftToLegal: Int, val legalHours: Int = 52) {
    /** "38시간 · 야간 2번 · 52시간까지 14시간", or "… · 52시간 3시간 넘음". */
    val text: String get() = buildString {
        append(HoursFormat.korean(minutes))
        if (nights > 0) append(" · 야간 ${nights}번")
        if (minutes > 0) append(if (leftToLegal >= 0) " · ${legalHours}시간까지 ${HoursFormat.korean(leftToLegal)}" else " · ${legalHours}시간보다 ${HoursFormat.korean(-leftToLegal)} 넘음")
    }
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
    companion object

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
            overLegalMinutes = limits.legalWeeklyMinutes?.let { (total - it).coerceAtLeast(0) } ?: 0,
        )
    }

    /**
     * [CAL-34][CAL-40] I 리뷰 I6·I19: a week in one line's worth: its hours, how many nights, and what is left before
     * 주 52시간 (negative when over). For the week view's total line and 통계's "다음 주 예상", which reads the
     * coming week from the roster before it is worked.
     */
    fun glance(containing: LocalDate): WeekGlance {
        val w = week(containing)
        val nights = resolver.resolveRange(w.start, w.end).count(com.bamnat.core.work.ShiftHealth::night)
        return WeekGlance(w.start, w.totalMinutes, nights, limits.legalWeeklyHours * 60 - w.totalMinutes, limits.legalWeeklyHours)
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

/**
 * [PAYX-29] For [HoursException.SPECIAL_INDUSTRY]: the days whose work ends less than 11 hours before the next day's work
 * starts, with the rest in minutes. Shifts and extra work with clock times only; a day with hours only is left out.
 */
fun WorkHoursCalculator.Companion.shortRests(days: List<com.bamnat.core.schedule.ResolvedDay>): List<Pair<LocalDate, Int>> {
    val spans = days.mapNotNull { d ->
        val starts = listOfNotNull(d.shiftFrom) + d.extras.mapNotNull { it.from }
        val ends = listOfNotNull(d.shiftTo) + d.extras.mapNotNull { it.to }
        if (starts.isEmpty() || ends.isEmpty()) null else Triple(d.date, starts.min(), ends.max())
    }.sortedBy { it.second }
    return spans.zipWithNext().mapNotNull { (a, b) ->
        val rest = java.time.Duration.between(a.third, b.second).toMinutes().toInt()
        if (rest in 0 until HoursException.REST_MINUTES) a.first to rest else null
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
