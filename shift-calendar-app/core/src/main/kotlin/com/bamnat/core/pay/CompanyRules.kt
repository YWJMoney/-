package com.bamnat.core.pay

import com.bamnat.core.model.ExtraWork

/** How a company pays one kind of extra work marked on the calendar (잔업·특근·대근). */
enum class ExtraBasis(val label: String) {
    /** Sorted into overtime, night and holiday minutes like any other work. */
    LEGAL("법정 기준대로"),
    /** 통상시급 × the company's multiplier, e.g. 대근 200%. */
    HOURLY_RATE("통상시급 × 배율"),
    /** A fixed amount each time, e.g. 대근 1회 80,000원. */
    FIXED_PER_TIME("1회 정액"),
    /** A fixed amount per hour, e.g. 특근 시간당 20,000원. */
    FIXED_PER_HOUR("시간당 정액"),
    /** 통상시급 × (100% + a premium per block of hours), e.g. 8시간 100%, 다음 4시간 150% [PAYX-07]. */
    TIERED("시간 구간별 가산"),
}

/** [minutes] of work, in shift order, paid with [premium] on top of 100% (0.5 = 150%). */
data class Tier(val minutes: Int, val premium: Double)

data class ExtraRule(
    val basis: ExtraBasis = ExtraBasis.LEGAL,
    /** For [ExtraBasis.HOURLY_RATE]: 1.5 = 150%. */
    val multiplier: Double = 1.5,
    /** For the fixed bases: won per time or per hour. */
    val amount: Long = 0,
    /** Hours the company credits at least, e.g. 대근은 최소 8시간 인정. 0 = actual hours. */
    val minMinutes: Int = 0,
    /** Also add the night premium for the 22~06 part of this work. */
    val nightPremium: Boolean = true,
    /** For [ExtraBasis.TIERED]: blocks for a day shift; the last block repeats for any more hours. */
    val dayTiers: List<Tier> = DEFAULT_DAY_TIERS,
    /** For [ExtraBasis.TIERED]: blocks when the work is a night shift (at least half in 22~06). */
    val nightTiers: List<Tier> = DEFAULT_NIGHT_TIERS,
) {
    /** Money for [minutes] of this work at [hourly], by the tiers for a day or night shift. */
    fun tieredAmount(minutes: Int, hourly: Long, night: Boolean): Long {
        val tiers = (if (night) nightTiers else dayTiers).ifEmpty { listOf(Tier(minutes, 0.0)) }
        var left = minutes
        var total = 0.0
        for ((i, t) in tiers.withIndex()) {
            if (left <= 0) break
            val m = if (i == tiers.lastIndex) left else minOf(left, t.minutes)
            total += m / 60.0 * hourly * (1.0 + t.premium)
            left -= m
        }
        return Math.round(total)
    }

    fun describeTiers(night: Boolean): String = (if (night) nightTiers else dayTiers).joinToString(" → ") {
        "${PayCalculator.hours(it.minutes)} ${PayCalculator.pct(1.0 + it.premium)}"
    }

    companion object {
        val DEFAULT_DAY_TIERS = listOf(Tier(480, 0.0), Tier(240, 0.5))
        val DEFAULT_NIGHT_TIERS = listOf(Tier(240, 0.5), Tier(240, 0.75), Tier(240, 1.0))
    }

    fun describe(): String = when (basis) {
        ExtraBasis.LEGAL -> basis.label
        ExtraBasis.HOURLY_RATE -> "통상시급 × ${PayCalculator.pct(multiplier)}"
        ExtraBasis.FIXED_PER_TIME -> "1회 ${won(amount)}"
        ExtraBasis.FIXED_PER_HOUR -> "시간당 ${won(amount)}"
        ExtraBasis.TIERED -> "주간 ${describeTiers(false)} · 야간 ${describeTiers(true)}"
    } + (if (minMinutes > 0) ", 최소 ${PayCalculator.hours(minMinutes)} 인정" else "") +
        (if (basis != ExtraBasis.LEGAL && !nightPremium) ", 야간 가산 없음" else "")
}

/** What work on a day off counts as. Companies decide this in the 취업규칙 or by agreement. */
enum class OffDayWork(val label: String, val detail: String) {
    HOLIDAY("휴일근로", "8시간까지 150%, 넘으면 200%"),
    OVERTIME("연장근로", "모든 시간 150%"),
}

/** Rounding of overtime minutes per day, e.g. 30분 단위 버림. */
data class MinuteRounding(val unit: Int = 1, val rounding: Rounding = Rounding.DOWN) {
    fun apply(minutes: Int): Int {
        if (unit <= 1 || minutes <= 0) return minutes
        return when (rounding) {
            Rounding.DOWN -> minutes / unit * unit
            Rounding.UP -> (minutes + unit - 1) / unit * unit
            Rounding.NEAREST -> (minutes + unit / 2) / unit * unit
        }
    }
}

/**
 * The company's own pay rules on top of the law [PAY-06, PAY-07, PAYX-07]. Every field has the
 * legal default, so an empty [CompanyRules] calculates by the law alone. Premiums are the part
 * on top of 100%; null means the legal rate from [Rates].
 */
data class CompanyRules(
    val overtimePremium: Double? = null,
    val nightPremium: Double? = null,
    val holidayPremiumWithin8h: Double? = null,
    val holidayPremiumOver8h: Double? = null,
    val offDayWork: OffDayWork = OffDayWork.HOLIDAY,
    /** 공휴일·근로자의 날 work counts as holiday work (no 휴일 대체). */
    val publicHolidaysPaid: Boolean = true,
    val extra: Map<ExtraWork, ExtraRule> = emptyMap(),
    val overtimeRounding: MinuteRounding = MinuteRounding(),
    /** 시급제 주휴수당 (근로기준법 제55조, 주 15시간 이상). */
    val weeklyHolidayPay: Boolean = true,
    /** 연차 days the company grants this year; null when not entered [FEAT-10]. */
    val annualLeaveDays: Double? = null,
    /** 연차 carried over from last year (이월), on top of [annualLeaveDays] [PAY-12][REL-08]. */
    val annualLeaveCarry: Double = 0.0,
    /**
     * A scheduled shift on a 공휴일 is paid like this kind of extra work on top of the salary,
     * e.g. 대근 방식 [PAYX-07]; null uses the legal holiday premiums.
     */
    val publicHolidayAs: ExtraWork? = null,
    /** 연차 taken on a 공휴일 does not use up the year's days (some companies) [FEAT-10]. */
    val holidayLeaveFree: Boolean = false,
    /**
     * [PAYX-11] A rule for one kind of extra work when it covers a given shift type, e.g. 대근 on
     * a 나이트 paid 1회 10만원 and on a 데이 8만원. Falls back to [extra] for other shifts.
     */
    val extraByShift: Map<ExtraWork, Map<Long, ExtraRule>> = emptyMap(),
    /**
     * [PAYX-12] The company's night window, e.g. 23:00~07:00; null uses the legal 22:00~06:00.
     * Night minutes of every shift on the calendar are counted in it, base shifts included.
     */
    val nightStart: java.time.LocalTime? = null,
    val nightEnd: java.time.LocalTime? = null,
    /** [PAYX-09] What this person's company calls each kind (e.g. 잔업 → 조출); filled from settings. */
    val extraNames: Map<ExtraWork, String> = emptyMap(),
) {
    fun extraRule(kind: ExtraWork?): ExtraRule? = kind?.let { extra[it] }?.takeIf { it.basis != ExtraBasis.LEGAL }

    /** The rule for [kind] on a day whose covered shift is [shiftTypeId], per shift first [PAYX-11]. */
    fun extraRule(kind: ExtraWork?, shiftTypeId: Long?): ExtraRule? =
        kind?.let { k -> shiftTypeId?.let { extraByShift[k]?.get(it) }?.takeIf { it.basis != ExtraBasis.LEGAL } } ?: extraRule(kind)

    fun extraName(kind: ExtraWork): String = extraNames[kind]?.takeIf { it.isNotBlank() } ?: kind.label

    val hasNightWindow: Boolean get() = nightStart != null && nightEnd != null && nightStart != nightEnd

    /** The night window as text, e.g. "23~07시". */
    fun nightLabel(rates: Rates): String {
        val s = if (hasNightWindow) nightStart!! else rates.nightStart
        val e = if (hasNightWindow) nightEnd!! else rates.nightEnd
        fun t(x: java.time.LocalTime) = if (x.minute == 0) "%02d".format(x.hour) else "%02d:%02d".format(x.hour, x.minute)
        return "${t(s)}~${t(e)}시"
    }

    /** [rates] with the company's night window in place of the law's. */
    fun applyNight(rates: Rates): Rates = if (hasNightWindow) rates.copy(nightStart = nightStart!!, nightEnd = nightEnd!!) else rates
}
