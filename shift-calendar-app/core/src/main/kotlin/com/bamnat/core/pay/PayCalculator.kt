package com.bamnat.core.pay

import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/** What a line is, so screens can group and colour it. */
enum class LineKind { BASE, OVERTIME, NIGHT, HOLIDAY, ALLOWANCE }

/**
 * One line of the result, explaining itself [PAY-09]: the formula, the minutes, the hourly rate
 * and multiplier that went in, the legal basis and the rate version.
 */
data class PayLine(
    val kind: LineKind,
    val label: String,
    val amount: Long,
    val formula: String,
    val minutes: Int = 0,
    val hourly: Long = 0,
    val multiplier: Double = 0.0,
    val basis: String = "",
    val estimated: Boolean = false,
)

data class MonthPay(
    val month: YearMonth,
    val lines: List<PayLine>,
    val ordinaryHourly: Long,
    val ratesVersion: String,
    val minutes: List<DayMinutes>,
    val warnings: List<String>,
) {
    val total: Long get() = lines.sumOf { it.amount }
    val estimated: Boolean get() = lines.any { it.estimated } || minutes.any { it.estimated }
}

/**
 * Turns a month of resolved days into pay [PAY-01, PAY-04..06]. Pure: everything comes in through the
 * arguments, so the same inputs always give the same result (see [CalcSnapshot]).
 */
class PayCalculator(
    private val rateTable: List<Rates> = RateTable.builtIn,
) {
    fun calculate(
        profile: PayProfile,
        month: YearMonth,
        resolve: (LocalDate) -> ResolvedDay,
        isHoliday: (LocalDate) -> Boolean = { false },
    ): MonthPay {
        val rates = RateTable.on(month.atDay(1), rateTable)
        val premiums = profile.fiveOrMoreEmployees
        val classifier = MinuteClassifier(
            rates = { RateTable.on(it, rateTable) },
            options = ClassifyOptions(profile.dailyLimitMinutes, profile.weeklyLimitMinutes, profile.weekStart),
        )
        // Start at the week containing the 1st so weekly overtime counts the days before it.
        val from = month.atDay(1).with(TemporalAdjusters.previousOrSame(profile.weekStart))
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(month.atEndOfMonth()) }
            .map(resolve).toList()
        val minutes = classifier.classify(days, isHoliday).filter { YearMonth.from(it.date) == month }
        val monthDays = days.filter { YearMonth.from(it.date) == month }

        val base = profile.base.on(month)
        val ordinaryMonthly = profile.items.filter { it.ordinary && it.cadence == Cadence.MONTHLY }
            .sumOf { it.amount.on(month).value }
        val hourly = when (profile.wageType) {
            WageType.MONTHLY -> ((base.value + ordinaryMonthly).toDouble() / profile.monthlyHours).roundToLong()
            WageType.HOURLY -> base.value
        }
        val hourlyFormula = when (profile.wageType) {
            WageType.MONTHLY -> "(기본급 ${won(base.value)}" +
                (if (ordinaryMonthly > 0) " + 통상 수당 ${won(ordinaryMonthly)}" else "") +
                ") ÷ ${profile.monthlyHours}시간"
            WageType.HOURLY -> "시급"
        }

        val lines = mutableListOf<PayLine>()
        val warnings = mutableListOf<String>()
        val baseEstimated = "base" in profile.estimatedFields || base.source == ValueSource.RAISED

        val regular = minutes.sumOf { it.regular }
        val overtime = minutes.sumOf { it.overtime }
        val night = minutes.sumOf { it.night }
        val hWithin = minutes.sumOf { it.holidayWithin8h }
        val hOver = minutes.sumOf { it.holidayOver8h }

        when (profile.wageType) {
            WageType.MONTHLY -> lines += PayLine(
                LineKind.BASE, "기본급", base.value,
                formula = when (base.source) {
                    ValueSource.RAISED -> "${won(base.basedOn!!.value)}에 ${profile.base.raise!!.describe()} ${base.raisesApplied}회 적용"
                    else -> "입력한 월 기본급"
                },
                estimated = baseEstimated,
            )
            WageType.HOURLY -> lines += line(LineKind.BASE, "기본 근무", regular, hourly, 1.0, "소정근로 시간 × 시급", "", baseEstimated)
        }

        // Overtime: hours beyond the contract are paid in full, plus the premium where the law applies.
        val otMultiplier = 1.0 + if (premiums) rates.overtimePremium else 0.0
        if (overtime > 0) lines += line(
            LineKind.OVERTIME, "연장근로", overtime, hourly, otMultiplier,
            "하루 8시간·주 40시간 초과와 직접 넣은 연장", "근로기준법 제56조 제1항", baseEstimated,
        )
        if (night > 0 && premiums) lines += line(
            LineKind.NIGHT, "야간근로 가산", night, hourly, rates.nightPremium,
            "22시~06시 근무에 더해 주는 가산분", "근로기준법 제56조 제3항", baseEstimated,
        )
        val holidayBase = if (profile.wageType == WageType.HOURLY) 1.0 else profile.holidayPayMode.baseMultiplier
        if (hWithin > 0) lines += line(
            LineKind.HOLIDAY, "휴일근로 (8시간 이내)", hWithin, hourly,
            holidayBase + if (premiums) rates.holidayPremiumWithin8h else 0.0,
            "휴일 근무 8시간까지" + if (profile.wageType == WageType.MONTHLY) " (${profile.holidayPayMode.label})" else "",
            "근로기준법 제56조 제2항 제1호", baseEstimated || "holidayPayMode" in profile.estimatedFields,
        )
        if (hOver > 0) lines += line(
            LineKind.HOLIDAY, "휴일근로 (8시간 초과)", hOver, hourly,
            holidayBase + if (premiums) rates.holidayPremiumOver8h else 0.0,
            "휴일 근무 8시간 넘는 부분", "근로기준법 제56조 제2항 제2호", baseEstimated,
        )

        for (item in profile.items) {
            val amount = item.amount.on(month)
            val count = when (item.cadence) {
                Cadence.MONTHLY -> 1
                Cadence.PER_WORK_DAY -> monthDays.count { it.isWorkDay || it.holidayWorkMinutes > 0 }
                Cadence.PER_NIGHT_SHIFT -> monthDays.count { it.isWorkDay && isNightShift(it, rates) }
                Cadence.PER_SHIFT_TYPE -> monthDays.count { it.isWorkDay && it.shiftType?.id in item.shiftTypeIds }
            }
            if (count == 0 || amount.value == 0L) continue
            lines += PayLine(
                LineKind.ALLOWANCE, item.name, amount.value * count,
                formula = if (count == 1) "${item.cadence.label} ${won(amount.value)}" else "${won(amount.value)} × ${count}회",
                basis = if (item.ordinary) "통상임금에 포함" else "",
                estimated = amount.source == ValueSource.RAISED || item.id in profile.estimatedFields,
            )
        }

        if (hourly in 1 until rates.minimumHourlyWage) {
            warnings += "통상시급 ${won(hourly)}이 ${month.year}년 최저임금 ${won(rates.minimumHourlyWage)}보다 낮아요. 입력값을 확인해 주세요"
        }
        if (!premiums) warnings += "5인 미만 사업장이라 연장·야간·휴일 가산을 넣지 않았어요"

        return MonthPay(month, lines, hourly, rates.version, minutes, warnings).withHourlyNote(hourlyFormula)
    }

    private fun MonthPay.withHourlyNote(formula: String) =
        copy(lines = lines.map { if (it.hourly > 0) it.copy(formula = "${it.formula} · 통상시급 = $formula") else it })

    private fun line(
        kind: LineKind, label: String, minutes: Int, hourly: Long, multiplier: Double,
        what: String, basis: String, estimated: Boolean,
    ) = PayLine(
        kind, label,
        amount = (minutes / 60.0 * hourly * multiplier).roundToLong(),
        formula = "$what: ${hours(minutes)} × ${won(hourly)} × ${pct(multiplier)}",
        minutes = minutes, hourly = hourly, multiplier = multiplier, basis = basis, estimated = estimated,
    )

    /** A shift with at least half its time in the night window counts as a night shift. */
    private fun isNightShift(day: ResolvedDay, rates: Rates): Boolean {
        val s = day.start ?: return false
        val e = day.end ?: return false
        val from = day.date.atTime(s)
        val to = if (e.isAfter(s)) day.date.atTime(e) else day.date.plusDays(1).atTime(e)
        val total = java.time.Duration.between(from, to).toMinutes()
        val night = MinuteClassifier({ rates }).nightMinutes(from, to, rates)
        return total > 0 && night * 2 >= total
    }

    companion object {
        fun hours(minutes: Int): String =
            if (minutes % 60 == 0) "${minutes / 60}시간" else "${minutes / 60}시간 ${minutes % 60}분"

        fun pct(m: Double): String = "${(m * 100).roundToLong()}%"
    }
}
