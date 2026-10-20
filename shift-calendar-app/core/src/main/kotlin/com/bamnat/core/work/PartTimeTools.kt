package com.bamnat.core.work

import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.TakeHome
import com.bamnat.core.pay.TaxMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** Weeks in an average month (365 ÷ 7 ÷ 12), the factor pay guides use for 월 환산. */
const val WEEKS_PER_MONTH = 365.0 / 7 / 12

/**
 * [HRLY-09] One part-time offer as the user types it for 두 알바 비교: the wage and hours, whether the
 * place pays 주휴수당, night and holiday hours and whether it has 5 people or more (가산 50%), how tax
 * comes off, and the commute both ways for each day of work.
 */
data class JobOffer(
    val name: String = "",
    val hourlyWage: Long = 0,
    val hoursPerWeek: Double = 0.0,
    val daysPerWeek: Int = 5,
    /** The place pays 주휴수당 when the week reaches 15 hours (the law says it must; some places split shifts to avoid it). */
    val holidayPay: Boolean = true,
    val nightHoursPerWeek: Double = 0.0,
    val holidayHoursPerWeek: Double = 0.0,
    val fiveOrMore: Boolean = true,
    val taxMode: TaxMode = TaxMode.NONE,
    /** Minutes there and back on a working day. */
    val commuteMinutes: Int = 0,
    /** Fare or fuel there and back on a working day. */
    val commuteCost: Long = 0,
)

/** [HRLY-09] What an offer comes to in a month and per hour really spent on it. */
data class OfferResult(
    val offer: JobOffer,
    val weeklyBase: Long,
    val weeklyHolidayPay: Long,
    val weeklyPremium: Long,
    val monthlyGross: Long,
    val deductions: List<Pair<String, Long>>,
    val monthlyNet: Long,
    val monthlyCommuteCost: Long,
    /** What is left after tax and the commute. */
    val monthlyKept: Long,
    val workHoursMonth: Double,
    val commuteHoursMonth: Double,
    val holidayEligible: Boolean,
    val belowMinimum: Boolean,
) {
    /** 실수령 ÷ the hours worked. */
    val perWorkHour: Long get() = if (workHoursMonth <= 0) 0 else (monthlyNet / workHoursMonth).roundToLong()

    /** [HRLY-09] 시간당 실제 수익: what is kept after tax and fares ÷ (work + commute) hours. */
    val realPerHour: Long get() = (workHoursMonth + commuteHoursMonth).let { if (it <= 0) 0 else (monthlyKept / it).roundToLong() }
}

/**
 * [HRLY-09] 두 알바 조건 비교: each offer worked out the same way as the calendar's own pay — 주휴 is
 * weekly hours ÷ 40 × 8 (at most 8) from 15 hours a week ([HourlyWeek]), night (22~06시) and holiday
 * hours and weekly hours over 40 take 50% more at a place of 5 or more, a month is [WEEKS_PER_MONTH]
 * weeks, and tax is [TakeHome.estimate]. 시간당 실제 수익 also takes off fares and counts the commute
 * as time spent, so a nearer job with a lower wage can come out ahead.
 */
object OfferCompare {
    fun of(offer: JobOffer, minimumWage: Long = 10_320): OfferResult {
        val wage = offer.hourlyWage.coerceAtLeast(0)
        val hours = offer.hoursPerWeek.coerceIn(0.0, 7 * 24.0)
        val base = (hours * wage).roundToLong()
        val week = HourlyWeek(LocalDate.MIN, (hours * 60).roundToLong().toInt(), 0)
        val eligible = offer.holidayPay && week.holidayPay
        val holiday = if (eligible) (week.holidayPayMinutes / 60.0 * wage).roundToLong() else 0
        val premiumHours = if (!offer.fiveOrMore) 0.0 else
            offer.nightHoursPerWeek.coerceIn(0.0, hours) + offer.holidayHoursPerWeek.coerceIn(0.0, hours) + (hours - 40).coerceAtLeast(0.0)
        val premium = (premiumHours * wage * 0.5).roundToLong()
        val gross = ((base + holiday + premium) * WEEKS_PER_MONTH).roundToLong()
        val tax = TakeHome.estimate(gross, offer.taxMode)
        val days = offer.daysPerWeek.coerceIn(0, 7) * WEEKS_PER_MONTH
        val fares = (offer.commuteCost.coerceAtLeast(0) * days).roundToLong()
        return OfferResult(
            offer = offer,
            weeklyBase = base,
            weeklyHolidayPay = holiday,
            weeklyPremium = premium,
            monthlyGross = gross,
            deductions = tax.deductions,
            monthlyNet = tax.net,
            monthlyCommuteCost = fares,
            monthlyKept = tax.net - fares,
            workHoursMonth = hours * WEEKS_PER_MONTH,
            commuteHoursMonth = offer.commuteMinutes.coerceAtLeast(0) / 60.0 * days,
            holidayEligible = eligible,
            belowMinimum = wage in 1 until minimumWage,
        )
    }

    /** The index of the offer that keeps the most per hour really spent; null with fewer than two worked out. */
    fun best(results: List<OfferResult>): Int? =
        results.takeIf { list -> list.count { it.offer.hourlyWage > 0 && it.offer.hoursPerWeek > 0 } >= 2 }
            ?.withIndex()?.filter { it.value.offer.hourlyWage > 0 }?.maxByOrNull { it.value.realPerHour }?.index
}

/**
 * [HRLY-10] 근로계약서: the photos kept on the phone and the few things the user types from it, so the
 * contract is at hand when pay or hours are in question, and its end is not missed.
 */
data class WorkContract(
    /** Photo file names in the calendar's contract folder, first page first. */
    val photos: List<String> = emptyList(),
    val hourlyWage: Long = 0,
    val workDays: Set<DayOfWeek> = emptySet(),
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val note: String = "",
) {
    val isEmpty: Boolean get() = photos.isEmpty() && hourlyWage == 0L && workDays.isEmpty() && start == null && end == null && note.isBlank()

    /** Days from [today] to the contract's last day; negative once it has passed, null without an end. */
    fun daysLeft(today: LocalDate): Long? = end?.let { ChronoUnit.DAYS.between(today, it) }

    /** [HRLY-10] Ends within [within] days (or ended in the last week), so 통계 shows a reminder. */
    fun endingSoon(today: LocalDate, within: Int = ENDING_SOON_DAYS): Boolean = daysLeft(today)?.let { it in -7..within.toLong() } == true

    /** The typed wage differs from the pay setup's, worth a look. */
    fun wageDiffers(payWage: Long?): Boolean = hourlyWage > 0 && payWage != null && payWage > 0 && payWage != hourlyWage

    companion object {
        const val ENDING_SOON_DAYS = 30
        const val MAX_PHOTOS = 6
    }
}

/**
 * [HRLY-11] 퇴사 정리: what is still owed when a part-time job ends, from the last day worked and the
 * last day employed (they can differ: 사직서에 적은 날). Every figure is a reference, labelled so.
 */
object QuitCheck {
    /**
     * [HRLY-11] 마지막 주 주휴: since 고용노동부's 2021-08 change (주휴수당 산정방법 관련 행정해석 변경 지침),
     * the week's 주휴 is owed when its 소정근로일 were all worked (15시간 이상) and the employment lasted
     * the whole 주휴 week, even with no work planned the week after. The app assumes "the whole week"
     * means the last day employed is the week's last day or later (e.g. 월~금 근무, 일요일까지 재직).
     * Before the change, 다음 주 근로 예정 was needed, and the last week earned none.
     */
    data class LastWeek(
        val weekStart: LocalDate,
        val minutes: Int,
        val absent: Int,
        val employedThroughWeek: Boolean,
        val pay: Long,
    ) {
        val weekEnd: LocalDate get() = weekStart.plusDays(6)
        val enoughHours: Boolean get() = minutes >= HourlyWeek.HOLIDAY_PAY_MINUTES
        val owed: Boolean get() = pay > 0
    }

    fun lastWeek(week: HourlyWeek, lastEmployed: LocalDate, hourlyWage: Long): LastWeek {
        val through = !lastEmployed.isBefore(week.start.plusDays(6))
        val pay = if (through && week.holidayPay) (week.holidayPayMinutes / 60.0 * hourlyWage).roundToLong() else 0
        return LastWeek(week.start, week.minutes, week.absent, through, pay)
    }

    /** Days employed, the first and last day both counted (계속근로기간). */
    fun daysEmployed(hire: LocalDate, lastEmployed: LocalDate): Long = ChronoUnit.DAYS.between(hire, lastEmployed) + 1

    /**
     * [HRLY-11] 퇴직금 (근로자퇴직급여 보장법 제4·8조): employed a year or more at 15 hours a week or more
     * on average. Owed = 1일 평균임금 × 30 × 재직일수 ÷ 365, where 평균임금 is the last three months' pay ÷
     * their days. Without that pay, the weekly pay with 주휴 ÷ 7 stands in for it.
     */
    data class Severance(val eligible: Boolean, val years: Double, val averageDaily: Long, val amount: Long, val reason: String)

    fun severance(hire: LocalDate?, lastEmployed: LocalDate, weeklyHours: Double, hourlyWage: Long, threeMonthPay: Long? = null): Severance {
        if (hire == null || hire.isAfter(lastEmployed)) return Severance(false, 0.0, 0, 0, "입사일을 알아야 계산할 수 있어요")
        val days = daysEmployed(hire, lastEmployed)
        val years = days / 365.0
        val week = HourlyWeek(lastEmployed, (weeklyHours * 60).roundToLong().toInt(), 0)
        val weeklyPay = ((week.minutes + week.holidayPayMinutes) / 60.0 * hourlyWage)
        val threeMonthDays = ChronoUnit.DAYS.between(lastEmployed.plusDays(1).minusMonths(3), lastEmployed.plusDays(1)).coerceAtLeast(1)
        val daily = (threeMonthPay?.takeIf { it > 0 }?.let { it.toDouble() / threeMonthDays } ?: (weeklyPay / 7)).roundToLong()
        val reason = when {
            days < 365 -> "1년이 안 돼서 퇴직금이 없어요 (${days}일 다녔어요)"
            weeklyHours < 15 -> "주 15시간이 안 돼서 퇴직금이 없어요"
            else -> "1년 이상, 주 15시간 이상이라 퇴직금을 받아요"
        }
        val eligible = days >= 365 && weeklyHours >= 15
        return Severance(eligible, years, daily, if (eligible) (daily * 30.0 * days / 365).roundToLong() else 0, reason)
    }

    /**
     * [HRLY-11] Annual leave made in the job's last stretch, for 미사용 연차수당 (근로기준법 제60조; 5인 이상만):
     * one day a month in the first year (at most 11), then 15 a year (+1 every two years after the first,
     * at most 25) once the year is done and the employment goes on the day after (대법원 2021다227100).
     * Leave runs in proportion to hours under 40 a week; none under 15. Used days are the user's to take off.
     */
    fun leaveDays(hire: LocalDate?, lastEmployed: LocalDate, weeklyHours: Double): Int {
        if (hire == null || weeklyHours < 15 || hire.isAfter(lastEmployed)) return 0
        val months = ChronoUnit.MONTHS.between(hire, lastEmployed.plusDays(1)).toInt()
        if (months < 12) return months.coerceAtMost(11)
        // Whole years done with a day after each still employed.
        val years = ChronoUnit.YEARS.between(hire, lastEmployed).toInt()
        if (years < 1) return 11
        return (15 + (years - 1) / 2).coerceAtMost(25)
    }

    /** One day of leave in won: the day's 소정근로시간 (weekly ÷ 5, at most 8) × the wage. */
    fun leaveDayPay(weeklyHours: Double, hourlyWage: Long): Long = ((weeklyHours / 5).coerceAtMost(8.0) * hourlyWage).roundToLong()

    /** [HRLY-11] Pay owed on leaving must be paid within 14 days (근로기준법 제36조). */
    fun payBy(lastEmployed: LocalDate): LocalDate = lastEmployed.plusDays(14)

    /**
     * [HRLY-11] 퇴사 뒤 근무표 멈추기: every repeating schedule ends on [last]; those that start later go.
     * Days already written by hand stay as they are.
     */
    fun endPatterns(patterns: List<WorkPattern>, last: LocalDate): List<WorkPattern> =
        patterns.filter { !it.startDate.isAfter(last) }
            .map { p -> if (p.endDate != null && !p.endDate!!.isAfter(last)) p else p.withRange(p.startDate, last) }
}
