package com.bamnat.core.work

import com.bamnat.core.money.DailySales
import com.bamnat.core.schedule.LegalBreak
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.roundToLong

/**
 * [SHOP-09] 간이과세 기준 (부가가치세법 제61조, 시행령 제109조): a shop whose sales of the last calendar year
 * (공급대가, 부가세 포함) are under 1억 400만 원 may be 간이과세자 (from 2024-07-01; checked again for 2026, no
 * change), and under 4,800만 원 it pays no 부가세 for the year (제69조). Going over moves a 간이과세자 to
 * 일반과세 from July of the next year. This year's sales so far and where the year is heading, 참고용.
 */
object SimpleTaxLimit {
    const val LIMIT = 104_000_000L
    const val EXEMPT = 48_000_000L
    /** From this share of the limit the card warns that the year is getting close. */
    const val NEAR_PERCENT = 80

    enum class Level { UNDER, NEAR, HEADING_OVER, OVER }

    data class Year(val year: Int, val sales: Long, val projected: Long?, val level: Level) {
        val percent: Int get() = (sales * 100 / LIMIT).toInt()
        val left: Long get() = (LIMIT - sales).coerceAtLeast(0)
    }

    /**
     * The year of [today] up to [today]. The year's end is guessed from the days so far once a month has
     * passed (sales ÷ days × the year's days); before that there is too little to go on.
     */
    fun of(today: LocalDate, sales: List<DailySales>): Year {
        val year = today.year
        val sum = sales.filter { it.date.year == year && !it.date.isAfter(today) }.sumOf { it.sales }
        val days = today.dayOfYear
        val projected = if (days >= 30) (sum.toDouble() / days * today.lengthOfYear()).roundToLong() else null
        val level = when {
            sum >= LIMIT -> Level.OVER
            projected != null && projected >= LIMIT -> Level.HEADING_OVER
            sum * 100 >= LIMIT * NEAR_PERCENT -> Level.NEAR
            else -> Level.UNDER
        }
        return Year(year, sum, projected, level)
    }
}

/** [SHOP-10] The day's weather as the user picked it. */
enum class Weather(val label: String) { SUNNY("맑음"), CLOUDY("흐림"), RAIN("비"), SNOW("눈") }

/** [SHOP-10] 날씨 메모: the weather of one day and, when typed, the temperature in °C. */
data class WeatherNote(val date: LocalDate, val weather: Weather, val temperature: Int? = null)

/** [SHOP-10] 비 오는 날 vs 맑은 날: average sales on the days of each weather, for days with sales. */
object WeatherSales {
    data class Row(val weather: Weather, val days: Int, val average: Long)

    /** Each weather with sales on at least one day from [from] to [to], in the order of [Weather]. */
    fun compare(notes: Collection<WeatherNote>, sales: List<DailySales>, from: LocalDate, to: LocalDate): List<Row> {
        val byDate = notes.associateBy { it.date }
        val days = sales.filter { it.sales > 0 && !it.date.isBefore(from) && !it.date.isAfter(to) }
            .mapNotNull { s -> byDate[s.date]?.let { it.weather to s.sales } }
        return Weather.entries.mapNotNull { w ->
            val list = days.filter { it.first == w }.map { it.second }
            if (list.isEmpty()) null else Row(w, list.size, list.sum() / list.size)
        }
    }

    /** How rainy days sell against sunny ones, in percent (−20 = 20% less); null without both. */
    fun rainAgainstSun(rows: List<Row>): Int? {
        val rain = rows.firstOrNull { it.weather == Weather.RAIN }?.average ?: return null
        val sun = rows.firstOrNull { it.weather == Weather.SUNNY }?.average?.takeIf { it > 0 } ?: return null
        return ((rain - sun) * 100.0 / sun).roundToLong().toInt()
    }
}

/** [SHOP-11] One weekday's hours of a staff member; an end before the start runs past midnight. */
data class StaffShift(val start: LocalTime, val end: LocalTime) {
    val spanMinutes: Int get() = ((end.toSecondOfDay() - start.toSecondOfDay()) / 60).let { if (it <= 0) it + 24 * 60 else it }
    /** Minutes paid: the span less the legal break (근로기준법 제54조) when [breaks] is on. */
    fun paidMinutes(breaks: Boolean): Int = spanMinutes - if (breaks) LegalBreak.minutesFor(spanMinutes) else 0
}

/** [SHOP-11] 직원 (알바생): the wage, whether 주휴수당 is paid, 4대보험 enrolment and the weekly schedule. */
data class Staff(
    val id: String,
    val name: String,
    val hourlyWage: Long = 10_320,
    val holidayPay: Boolean = true,
    val insured: Boolean = false,
    val shifts: Map<DayOfWeek, StaffShift> = emptyMap(),
) {
    fun weeklyMinutes(breaks: Boolean): Int = shifts.values.sumOf { it.paidMinutes(breaks) }
}

/** [SHOP-11] The shop's staff and how their cost is counted. */
data class StaffRoster(
    val staff: List<Staff> = emptyList(),
    /** Take the legal break off each shift. */
    val legalBreaks: Boolean = true,
)

/**
 * [SHOP-11] 인건비 자동 계산, the same way a part-timer's calendar counts pay: hours × wage on each day of
 * the month, 주휴 for each week whose 주휴일 (Sunday) falls in the month at weekly hours ÷ 40 × 8 from 15
 * hours ([HourlyWeek]), and, for staff enrolled in 4대보험, the employer's share for 2026 (국민연금 4.75%,
 * 건강보험 3.595%, 장기요양 = 건강보험료 × 13.14%, 고용보험 0.9% + 고용안정·직업능력개발 0.25% for under 150
 * people). 산재보험 depends on the trade and is left out; night and holiday premiums too.
 */
object LaborCost {
    const val PENSION = 0.0475
    const val HEALTH = 0.03595
    const val CARE_OF_HEALTH = 0.1314
    const val EMPLOYMENT = 0.0115

    data class Line(val staff: Staff, val workMinutes: Int, val base: Long, val holidayPay: Long, val insurance: Long) {
        val pay: Long get() = base + holidayPay
        val total: Long get() = pay + insurance
    }

    data class Month(val month: YearMonth, val lines: List<Line>) {
        val pay: Long get() = lines.sumOf { it.pay }
        val insurance: Long get() = lines.sumOf { it.insurance }
        val total: Long get() = lines.sumOf { it.total }
        val minutes: Int get() = lines.sumOf { it.workMinutes }

        /** [SHOP-11] 인건비율: the cost against [sales] in percent; null without sales. */
        fun ratio(sales: Long): Int? = if (sales <= 0) null else (total * 100.0 / sales).roundToLong().toInt()
    }

    fun employerInsurance(pay: Long): Long {
        val health = (pay * HEALTH).toLong() / 10 * 10
        return (pay * PENSION).toLong() / 10 * 10 + health + (health * CARE_OF_HEALTH).toLong() / 10 * 10 + (pay * EMPLOYMENT).toLong() / 10 * 10
    }

    fun line(s: Staff, month: YearMonth, breaks: Boolean): Line {
        val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
        val minutes = days.sumOf { d -> s.shifts[d.dayOfWeek]?.paidMinutes(breaks) ?: 0 }
        val base = (minutes / 60.0 * s.hourlyWage).roundToLong()
        val week = HourlyWeek(month.atDay(1), s.weeklyMinutes(breaks), 0)
        val sundays = days.count { it.dayOfWeek == DayOfWeek.SUNDAY }
        val holiday = if (s.holidayPay) (week.holidayPayMinutes / 60.0 * s.hourlyWage * sundays).roundToLong() else 0
        val insurance = if (s.insured) employerInsurance(base + holiday) else 0
        return Line(s, minutes, base, holiday, insurance)
    }

    fun month(roster: StaffRoster, month: YearMonth): Month = Month(month, roster.staff.map { line(it, month, roster.legalBreaks) })
}
