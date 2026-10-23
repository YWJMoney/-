package com.bamnat.core.pay

import java.time.YearMonth

import java.time.DayOfWeek
import java.time.LocalDate

/** How the base pay is set. */
enum class WageType {
    /** A monthly 기본급 that already holds the month's 소정근로 and paid holidays. */
    MONTHLY,
    /** 시급: paid by the hour. */
    HOURLY,
    /**
     * [PAYS-03] 일당: one day's pay for [PayProfile.dailyHours] of 소정근로, otherwise paid like an hourly
     * wage of 일당 ÷ 1일 소정근로시간 (근로기준법 시행령 제6조 제2항 제2호).
     */
    DAILY,
}

/** Job presets [PAYX-01]. Each fills sensible items and defaults; everything stays editable. */
enum class Occupation(val label: String) {
    NURSE("병원 간호사"),
    PART_TIME("시급 파트타임"),
    MANUFACTURING("제조·생산 교대"),
    CARE("요양·돌봄"),
    SECURITY("경비·시설 (24시간 맞교대)"),
    // [JOB-01][PAYX-01] 공무원 pay follows 공무원수당 등에 관한 규정, not 근로기준법's premiums.
    PUBLIC("공무원 (교대·현업)"),
    POLICE("경찰 (지구대·파출소 교대)"),
    FIRE("소방 (교대)"),
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
    /**
     * [PAYS-03] The legal premiums this allowance stands in for, e.g. a 야간수당 paid instead of the night
     * premium: only [LineKind.OVERTIME], [LineKind.NIGHT] and [LineKind.HOLIDAY] mean something. null
     * takes [defaultCovers], so allowances saved before this field keep their totals.
     */
    val covers: Set<LineKind>? = null,
    /** [PAYX-31] For a bonus paid as [percentOfBase]: the amount the percent is of. */
    val bonusBasis: BonusBasis = BonusBasis.BASE,
) {
    /** How many payments of this bonus fall in [month]: its fixed months plus any 명절 [PAYX-10]. */
    fun timesIn(month: java.time.YearMonth): Int =
        (if (month.monthValue in months) 1 else 0) + festivals.count { f ->
            listOf(month.year, month.year + 1).any { y -> f.payMonth(y, festivalMonthBefore) == month }
        }

    /** Payments in [year]; the 명절 months change every year. */
    fun timesInYear(year: Int): Int = (1..12).sumOf { timesIn(java.time.YearMonth.of(year, it)) }

    /**
     * Payments in a usual year: fixed months plus one per 명절. [PAYX-23] This, not [timesInYear], is the 연간
     * 지급 횟수 for 통상임금: a 명절상여 paid the month before can land twice in one calendar year (설 2027-02 paid
     * in January, 설 2028-01-26 paid in December 2027) and none in the next, but it is still one 설 상여 a year.
     */
    val yearlyTimes: Int get() = months.size + festivals.size

    val isFixedOvertime: Boolean get() = fixedOvertimeMinutes > 0 && cadence == Cadence.MONTHLY

    /** [PAYS-03] What [covers] is when not set: 연장 for a 고정 연장수당, nothing for any other allowance. */
    val defaultCovers: Set<LineKind> get() = if (isFixedOvertime) setOf(LineKind.OVERTIME) else emptySet()

    /** [PAYS-03] The premiums this allowance stands in for, as the calculator uses them, in [COVERABLE] order. */
    val coveredKinds: Set<LineKind> get() = (covers ?: defaultCovers).let { set -> COVERABLE.filter { it in set }.toSet() }

    /** [PAYS-03] Paid for night work (야간·나이트 수당), so it may stand in for the night premium. */
    val isNightAllowance: Boolean
        get() = cadence == Cadence.PER_NIGHT_SHIFT ||
            cadence != Cadence.BONUS && listOf("야간", "심야", "나이트", "night").any { name.contains(it, ignoreCase = true) }

    /** One payment of a [Cadence.BONUS] item given the month's 기본급. */
    fun bonusPayment(base: Long, on: java.time.YearMonth): Long =
        percentOfBase?.let { Math.round(base * it) } ?: amount.on(on).value

    /**
     * The monthly share of a bonus for 통상임금: a year's payments ÷ 12. Since 대법원 2024.12.19.
     * 2020다247190 (전원합의체), a bonus paid regularly on fixed terms is 통상임금 even when it
     * requires being employed on the pay date.
     */
    fun bonusMonthlyShare(base: Long, on: java.time.YearMonth): Long =
        Math.round(bonusPayment(base, on) * yearlyTimes / 12.0)

    companion object {
        /** [PAYS-03] The premium lines an allowance can stand in for; company rules for extra work never are. */
        val COVERABLE: List<LineKind> = listOf(LineKind.OVERTIME, LineKind.NIGHT, LineKind.HOLIDAY)
    }
}

/**
 * [PAYX-31] What a 상여 paid as a percent is a percent of. 취업규칙 say 기본급, 기본급+고정수당 (월정액) or 통상임금; which one
 * is the company's. The percent is of the month's amounts, bonuses left out, so a bonus never feeds itself.
 */
enum class BonusBasis(val label: String) {
    BASE("기본급"),
    BASE_FIXED("기본급+고정수당"),
    ORDINARY("통상임금"),
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
    /** Monthly base salary, the hourly wage for [WageType.HOURLY], or the 일당 for [WageType.DAILY]. */
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
    /** [PAYX-17] For a 2주 단위기간: a day one of the periods starts on; null runs them from a Monday. */
    val flexStart: LocalDate? = null,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    /**
     * Values the user skipped and the app guessed; shown with an "추정" label. [PAYS-01] Also the
     * 확인 필요 set: the keys of [Unknowns] for questions answered with 모르겠어요.
     */
    val estimatedFields: Set<String> = emptySet(),
    /** The company's own rules on top of the law [PAYX-07]. */
    val rules: CompanyRules = CompanyRules(),
    /** [PAYX-15] The pay period and pay day; the calendar month by default. */
    val period: PayPeriod = PayPeriod(),
    /**
     * [PAYS-03] For [WageType.DAILY]: minutes of 1일 소정근로 the 일당 pays for, at most 8 hours (480, the
     * default). The hourly rate is 일당 ÷ these hours.
     */
    val dailyHours: Int = 8 * 60,
    /** [PAYS-03] The 연봉 the monthly 기본급 was worked out from, to show how; kept on the phone only. */
    val annual: AnnualPay? = null,
    /** [PAYS-02] The last month matched against a payslip; kept on the phone only. */
    val verified: PayslipCheck? = null,
    /** [PAYS-06] Rules the user stated in their own words, paid on top of everything else. */
    val customRules: List<CustomRule> = emptyList(),
    /** [HRLY-04] How tax comes off this pay, for 예상 실수령; nothing by default. */
    val taxMode: TaxMode = TaxMode.NONE,
    /** [PAYR-04] 간이세액표 공제대상가족 수 (본인·배우자 포함, 1~20), for 예상 실수령's 근로소득세. */
    val taxFamily: Int = 1,
    /** [PAYR-04] Of [taxFamily], 8세 이상 20세 이하 자녀 (간이세액표 제3호). */
    val taxChildren: Int = 0,
    /**
     * [HRLY-05] 법정 휴게 자동 빼기: a day with no break set gets the break 근로기준법 제54조 asks for, 30 minutes
     * from 4 hours and an hour from 8 (see [com.bamnat.core.schedule.LegalBreak]).
     */
    val legalBreaks: Boolean = false,
    /** [HRLY-06] 수습 감액: days before this date pay [probationPercent] of the hourly wage; null for none. */
    val probationEnd: LocalDate? = null,
    val probationPercent: Int = 90,
    /** [HRLY-07] The weekday a 주휴 week starts on; null counts 주휴 by [weekStart] like the rest of the pay. */
    val holidayWeekStart: DayOfWeek? = null,
    /** [JOB-02] A public servant's 봉급표 계급·호봉, from which the 봉급 and 수당 are set; null when not picked. */
    val publicGrade: PublicGrade? = null,
    /**
     * [PAYX-24] 퇴사: the last day employed (재직 마지막 날), null while employed. With [hireDate] it sets the days the month's
     * 기본급 is cut to ([CompanyRules.proration]); days after it are not paid.
     */
    val leaveDate: LocalDate? = null,
    /**
     * [PAYX-25] 생산직 등 근로자 (소득세법 시행령 제17조 제1항: 공장·광산 생산직, 운전원, 경비·청소·돌봄·미용 등 단순노무): its
     * 연장·야간·휴일 수당 may be 비과세 under the limits of [TakeHome.productionFree].
     */
    val productionWorker: Boolean = false,
    /** [PAYX-25] 직전 과세기간 총급여액 (작년 연봉, 비과세 뺀 것); null when not entered, which is read as within the limit. */
    val previousYearPay: Long? = null,
    /** [LEDG-29] The 가계부 counts pay as the take-home that arrives on the pay day ([PayIncome]) instead of the month's 세전. */
    val ledgerTakeHome: Boolean = false,
) {
    /** [HRLY-07] The weekday 주휴 weeks start on. */
    val holidayWeekFrom: DayOfWeek get() = holidayWeekStart ?: weekStart

    /** [PAYX-24] Employed on [date]: on or after [hireDate] and on or before [leaveDate]. */
    fun employed(date: LocalDate): Boolean = hireDate?.let { !date.isBefore(it) } != false && leaveDate?.let { !date.isAfter(it) } != false

    /**
     * [PAYX-31] The amount [item]'s percent is of in [month]: 기본급, plus the fixed monthly allowances (not standing in
     * for premiums) for [BonusBasis.BASE_FIXED], or plus those in 통상임금 for [BonusBasis.ORDINARY].
     */
    fun bonusBase(item: PayItem, month: YearMonth): Long {
        val base = base.on(month).value
        fun fixed(ordinaryOnly: Boolean) = items.filter {
            it.cadence == Cadence.MONTHLY && !it.isFixedOvertime && it.coveredKinds.isEmpty() && (!ordinaryOnly || it.ordinary)
        }.sumOf { it.amount.on(month).value }
        return when (item.bonusBasis) {
            BonusBasis.BASE -> base
            BonusBasis.BASE_FIXED -> base + fixed(false)
            BonusBasis.ORDINARY -> base + fixed(true)
        }
    }

    /** [HRLY-06] True when [date] is paid at the 수습 rate. */
    fun onProbation(date: LocalDate): Boolean = probationEnd?.let { date.isBefore(it) } == true && probationPercent in 1..99
}
