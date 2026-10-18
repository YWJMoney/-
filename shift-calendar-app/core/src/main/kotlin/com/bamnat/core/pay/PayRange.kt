package com.bamnat.core.pay

import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.YearMonth

/**
 * [PAYS-01] How far one 확인 필요 answer moves the month, e.g. "이 답에 따라 −8.3만~+4.1만 원": [down] ≤ 0 ≤ [up]
 * in won, against the month with every assumed answer.
 */
data class UnknownEffect(
    val unknown: Unknown,
    val down: Long,
    val up: Long,
    /** The answers giving [down] and [up]; null when no answer moves the month that way. */
    val lowest: UnknownAnswer?,
    val highest: UnknownAnswer?,
) {
    val key: String get() = unknown.key
    /** Whether any answer changes the month at all. */
    val moves: Boolean get() = down != 0L || up != 0L
}

/**
 * [PAYS-01] The month as a range while some answers are 모르겠어요, e.g. "약 312만~341만 원". [base] is the month
 * with the assumed answers; [low] and [high] bound it over the other answers. Allowances whose amount is
 * unknown are in no figure ([MonthPay.missing] of [base] lists them).
 */
data class PayRange(
    val base: MonthPay,
    val low: Long,
    val high: Long,
    /** Every 확인 필요 key and its effect, in [Unknowns.of] order; amounts entered roughly have none. */
    val effects: List<UnknownEffect>,
    /** How many times the month was calculated. */
    val runs: Int,
) {
    /** The month with the assumed answers. */
    val total: Long get() = base.total
    /** No open answer changes the month: one figure, not a range. */
    val exact: Boolean get() = low == high

    companion object {
        /**
         * One run with the assumed answers, one per other answer of each unknown (its effect), then the lows
         * together and the highs together. The bounds come from those runs, not from adding the effects up:
         * answers overlap (5인 미만 drops the same premiums 감시·단속 would), so a sum would count them twice.
         * A single answer that goes further than the combination still widens the bound. Every run leaves out
         * the checks that calculate the month again. [rosterHours] is the roster's 월 소정근로시간; worked out
         * from [resolve] when 월 소정근로시간 is unknown and it is not given.
         */
        fun of(
            profile: PayProfile,
            month: YearMonth,
            resolve: (LocalDate) -> ResolvedDay,
            isHoliday: ((LocalDate) -> Boolean)? = null,
            calculator: PayCalculator = PayCalculator(),
            rosterHours: Int? = null,
        ): PayRange {
            var runs = 0
            fun run(p: PayProfile): MonthPay = calculator.calculate(p, month, resolve, checks = false, isHoliday = isHoliday).also { runs++ }
            val base = run(profile)
            val roster = rosterHours ?: if (Unknowns.MONTHLY_HOURS in profile.estimatedFields) rosterMonthlyHours(profile, month, resolve) else null
            val effects = Unknowns.of(profile, roster).map { u ->
                val moved = u.answers.map { it to run(it.apply(profile)).total - base.total }
                val low = moved.filter { it.second < 0 }.minByOrNull { it.second }
                val high = moved.filter { it.second > 0 }.maxByOrNull { it.second }
                UnknownEffect(u, low?.second ?: 0, high?.second ?: 0, low?.first, high?.first)
            }
            var low = base.total + (effects.minOfOrNull { it.down } ?: 0)
            var high = base.total + (effects.maxOfOrNull { it.up } ?: 0)
            val lows = effects.mapNotNull { it.lowest }
            val highs = effects.mapNotNull { it.highest }
            if (lows.size > 1) low = minOf(low, run(lows.fold(profile) { p, a -> a.apply(p) }).total)
            if (highs.size > 1) high = maxOf(high, run(highs.fold(profile) { p, a -> a.apply(p) }).total)
            return PayRange(base, low, high, effects, runs)
        }

        /** [PAY-04] The roster's 월 소정근로시간: a year of it from the pay period's start. */
        internal fun rosterMonthlyHours(profile: PayProfile, month: YearMonth, resolve: (LocalDate) -> ResolvedDay): Int =
            StandardHours.monthly(StandardHours.averageWeekly(profile.period.start(month), resolve = resolve))
    }
}
