package com.bamnat.core.pay

import java.time.YearMonth

import java.time.DayOfWeek
import java.time.LocalDate

enum class WageType { MONTHLY, HOURLY }

/** Job presets [PAYX-01]. Each fills sensible items and defaults; everything stays editable. */
enum class Occupation(val label: String) {
    NURSE("병원 간호사"),
    PART_TIME("시급 파트타임"),
    MANUFACTURING("제조·생산 교대"),
    CARE("요양·돌봄"),
    SECURITY("경비·시설 (24시간 맞교대)"),
    CUSTOM("직접 설정"),
}

/** How often an allowance is paid. */
enum class Cadence(val label: String) {
    MONTHLY("매달"),
    PER_WORK_DAY("근무일마다"),
    PER_NIGHT_SHIFT("야간근무마다"),
    PER_SHIFT_TYPE("특정 근무마다"),
    /** 상여금 paid in set months, a fixed amount or a percent of 기본급 [PAY-05]. */
    BONUS("정해진 달 상여"),
}

/** How a monthly-paid worker is paid for work on a paid holiday [PAY-06, 쟁점 처리]. */
enum class HolidayPayMode(val label: String, val baseMultiplier: Double) {
    /** The day's 100% is paid again on top of the salary, plus the premium (일반적 해석). */
    FULL("100% + 가산", 1.0),
    /** The salary already covers the day; only the premium is added. */
    PREMIUM_ONLY("가산만", 0.0),
}

/**
 * One allowance line, e.g. 나이트 수당 1회 30,000원 [PAY-05].
 * [ordinary] includes it in 통상임금, which raises the hourly rate used for premiums.
 */
data class PayItem(
    val id: String,
    val name: String,
    val amount: ValueTimeline,
    val cadence: Cadence,
    /** For [Cadence.PER_SHIFT_TYPE]: which shift types count. */
    val shiftTypeIds: Set<Long> = emptySet(),
    val ordinary: Boolean = false,
    val taxable: Boolean = true,
    /** For [Cadence.BONUS]: months it is paid (1..12). */
    val months: Set<Int> = emptySet(),
    /** For [Cadence.BONUS]: share of 기본급 per payment, 1.0 = 100%; null pays [amount]. */
    val percentOfBase: Double? = null,
    /**
     * A fixed monthly allowance paid for overtime in advance (고정 연장수당, 포괄임금): the hours
     * it is meant to cover, 0 when it is an ordinary allowance [PAY-05]. Never 통상임금.
     */
    val fixedOvertimeMinutes: Int = 0,
    /** For [Cadence.BONUS]: 명절상여 paid with 설날·추석, whose dates move with the lunar calendar [PAYX-10]. */
    val festivals: Set<Festival> = emptySet(),
    /** Pay the 명절상여 in the month before the holiday instead of the holiday's own month. */
    val festivalMonthBefore: Boolean = false,
) {
    /** How many payments of this bonus fall in [month]: its fixed months plus any 명절 [PAYX-10]. */
    fun timesIn(month: java.time.YearMonth): Int =
        (if (month.monthValue in months) 1 else 0) + festivals.count { f ->
            listOf(month.year, month.year + 1).any { y -> f.payMonth(y, festivalMonthBefore) == month }
        }

    /** Payments in [year]; the 명절 months change every year. */
    fun timesInYear(year: Int): Int = (1..12).sumOf { timesIn(java.time.YearMonth.of(year, it)) }

    /** Payments in a usual year, for labels: fixed months plus one per 명절. */
    val yearlyTimes: Int get() = months.size + festivals.size

    val isFixedOvertime: Boolean get() = fixedOvertimeMinutes > 0 && cadence == Cadence.MONTHLY

    /** One payment of a [Cadence.BONUS] item given the month's 기본급. */
    fun bonusPayment(base: Long, on: java.time.YearMonth): Long =
        percentOfBase?.let { Math.round(base * it) } ?: amount.on(on).value

    /**
     * The monthly share of a bonus for 통상임금: a year's payments ÷ 12. Since 대법원 2024.12.19.
     * 2020다247190 (전원합의체), a bonus paid regularly on fixed terms is 통상임금 even when it
     * requires being employed on the pay date.
     */
    fun bonusMonthlyShare(base: Long, on: java.time.YearMonth): Long =
        Math.round(bonusPayment(base, on) * timesInYear(on.year) / 12.0)
}

/** 명절 that a 명절상여 follows [PAYX-10]: lunar 1/1 and 8/15, converted for each year. */
enum class Festival(val label: String, val lunarMonth: Int, val lunarDay: Int) {
    SEOLLAL("설날", 1, 1),
    CHUSEOK("추석", 8, 15);

    fun date(year: Int): java.time.LocalDate? = com.bamnat.core.holiday.KoreanLunar.toSolar(year, lunarMonth, lunarDay)

    /** The month a bonus for this holiday in [year] is paid. */
    fun payMonth(year: Int, monthBefore: Boolean): java.time.YearMonth? =
        date(year)?.let { java.time.YearMonth.from(it).let { m -> if (monthBefore) m.minusMonths(1) else m } }
}

/**
 * [PAYX-15] The days one month's pay covers and when it is paid. Many companies close the month
 * early, e.g. 전월 21일~이번 달 20일, and pay on the 25th. The pay month is the month the period
 * ends in, so 9/21~10/20 is October's pay.
 */
data class PayPeriod(
    /** Last day of the period, 1..31 (shorter months use their last day); null = 말일. */
    val endDay: Int? = null,
    /** Day of the month pay arrives, 1..31; null when not entered. */
    val payDay: Int? = null,
    /** Pay arrives the month after the period ends (e.g. 말일 마감, 다음 달 10일 지급). */
    val payNextMonth: Boolean = false,
) {
    private fun endIn(m: YearMonth): LocalDate = m.atDay(minOf(endDay ?: 31, m.lengthOfMonth()))

    fun start(m: YearMonth): LocalDate = if (endDay == null) m.atDay(1) else endIn(m.minusMonths(1)).plusDays(1)
    fun end(m: YearMonth): LocalDate = endIn(m)
    fun contains(m: YearMonth, date: LocalDate): Boolean = !date.isBefore(start(m)) && !date.isAfter(end(m))

    /** The pay month whose period holds [date]. */
    fun monthOf(date: LocalDate): YearMonth {
        val m = YearMonth.from(date)
        return if (date.isAfter(end(m))) m.plusMonths(1) else m
    }

    /** When [m]'s pay arrives, or null when no pay day was entered. */
    fun payDate(m: YearMonth): LocalDate? = payDay?.let { d ->
        val pm = if (payNextMonth) m.plusMonths(1) else m
        pm.atDay(minOf(d, pm.lengthOfMonth()))
    }

    val isCalendarMonth: Boolean get() = endDay == null || endDay >= 31

    /** "9/21~10/20" for [m]. */
    fun label(m: YearMonth): String {
        val s = start(m); val e = end(m)
        return "${s.monthValue}/${s.dayOfMonth}~${e.monthValue}/${e.dayOfMonth}"
    }
}

/**
 * Everything about the worker's pay that does not change month to month [PAY-10].
 * Values that change over time (base pay, allowances) are [ValueTimeline]s.
 */
data class PayProfile(
    val occupation: Occupation,
    val wageType: WageType,
    /** Monthly base salary, or the hourly wage for [WageType.HOURLY]. */
    val base: ValueTimeline,
    val items: List<PayItem> = emptyList(),
    val hireDate: LocalDate? = null,
    /** 5인 이상 사업장: premiums for overtime, night and holiday work apply only then. */
    val fiveOrMoreEmployees: Boolean = true,
    /** 월 소정근로시간, 209 by default. */
    val monthlyHours: Int = 209,
    val holidayPayMode: HolidayPayMode = HolidayPayMode.FULL,
    /** null turns daily overtime off (탄력적 근로시간제 등). */
    val dailyLimitMinutes: Int? = 8 * 60,
    val weeklyLimitMinutes: Int? = 40 * 60,
    /** [PAYX-07] Scheduled hours are all 기본 근무; see [ClassifyOptions.scheduleIsRegular]. */
    val scheduleIsRegular: Boolean = false,
    /** [PAYX-07] 탄력적 근로시간제 단위기간, used with [scheduleIsRegular]; null when there is none. */
    val flexPeriod: FlexPeriod? = null,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    /** Values the user skipped and the app guessed; shown with an "추정" label. */
    val estimatedFields: Set<String> = emptySet(),
    /** The company's own rules on top of the law [PAYX-07]. */
    val rules: CompanyRules = CompanyRules(),
    /** [PAYX-15] The pay period and pay day; the calendar month by default. */
    val period: PayPeriod = PayPeriod(),
)
