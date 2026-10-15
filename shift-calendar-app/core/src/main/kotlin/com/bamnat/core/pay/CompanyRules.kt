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
}

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
) {
    fun describe(): String = when (basis) {
        ExtraBasis.LEGAL -> basis.label
        ExtraBasis.HOURLY_RATE -> "통상시급 × ${PayCalculator.pct(multiplier)}"
        ExtraBasis.FIXED_PER_TIME -> "1회 ${won(amount)}"
        ExtraBasis.FIXED_PER_HOUR -> "시간당 ${won(amount)}"
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
) {
    fun extraRule(kind: ExtraWork?): ExtraRule? = kind?.let { extra[it] }?.takeIf { it.basis != ExtraBasis.LEGAL }
}
