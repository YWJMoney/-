package com.bamnat.core.pay

import com.bamnat.core.model.ExtraKind
import java.time.DayOfWeek

/** How a company pays one kind of extra work marked on the calendar (잔업·특근·대근 …). */
enum class ExtraBasis(val label: String) {
    /** Sorted into 소정 외, overtime, night and holiday minutes like any other work. */
    LEGAL("법대로"),
    /** A fixed amount each time, e.g. 대근 1회 80,000원. */
    FIXED_PER_TIME("1회 정액"),
    /** [EXTRA-03] A share of 기본급 each time, e.g. 대근 1회 기본급의 10%. */
    PERCENT_OF_BASE("1회 기본급의 %"),
    /** A fixed amount per hour, e.g. 특근 시간당 20,000원. */
    FIXED_PER_HOUR("시간당 정액"),
    /** 통상시급 × the company's multiplier, e.g. 대근 200%. */
    HOURLY_RATE("통상시급 × 배율"),
    /** 통상시급 × (100% + a premium per block of hours), e.g. 8시간 100%, 다음 4시간 150% [PAYX-07]. */
    TIERED("시간 구간별 가산"),
}

/** [minutes] of work, in shift order, paid with [premium] on top of 100% (0.5 = 150%). */
data class Tier(val minutes: Int, val premium: Double)

/**
 * How one kind of extra work is paid [PAYX-07, EXTRA-03]: a base part ([basis]) and, on top of it,
 * the premiums the law gives the same hours when the company adds them, plus an amount each time.
 * The rule covers that piece of work only; the day's own shift stays as the law says.
 */
data class ExtraRule(
    val basis: ExtraBasis = ExtraBasis.LEGAL,
    /** For [ExtraBasis.HOURLY_RATE]: 1.5 = 150%. */
    val multiplier: Double = 1.5,
    /** For the fixed bases: won per time or per hour. */
    val amount: Long = 0,
    /** For [ExtraBasis.PERCENT_OF_BASE]: the share of 기본급 each time, 0.1 = 10%. */
    val percentOfBase: Double = 0.1,
    /**
     * For the per-time bases: the hours one payment covers, e.g. 8시간. Hours beyond it are paid
     * 100% of 통상시급, with the premiums below when the company adds them. Null covers every hour.
     */
    val coverMinutes: Int? = null,
    /** Hours the company credits at least, e.g. 대근은 최소 8시간 인정. 0 = actual hours. */
    val minMinutes: Int = 0,
    /** Also add the night premium for the 22~06 part of this work. */
    val nightPremium: Boolean = true,
    /** [EXTRA-03] Also add the 연장·휴일 premiums the law gives these hours, e.g. 대근 1회 + 연장 가산 50%. */
    val legalPremiums: Boolean = false,
    /** [EXTRA-03] Paid on top each time, e.g. 대근 수당 30,000원. Not 통상임금. */
    val bonus: Long = 0,
    /** For [ExtraBasis.TIERED]: blocks for a day shift; the last block repeats for any more hours. */
    val dayTiers: List<Tier> = DEFAULT_DAY_TIERS,
    /** For [ExtraBasis.TIERED]: blocks when the work is a night shift (at least half in 22~06). */
    val nightTiers: List<Tier> = DEFAULT_NIGHT_TIERS,
) {
    /** By law with nothing on top: the same as having no rule. */
    val isLegal: Boolean get() = basis == ExtraBasis.LEGAL && bonus <= 0

    /** A payment each time that stands for some hours of work. */
    val perTime: Boolean get() = basis == ExtraBasis.FIXED_PER_TIME || basis == ExtraBasis.PERCENT_OF_BASE

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

        /** 0.1 → "10%", 0.125 → "12.5%". */
        fun percent(share: Double): String {
            val p = share * 100
            return if (Math.abs(p - Math.round(p)) < 0.05) "${Math.round(p)}%" else "%.1f%%".format(p)
        }
    }

    /** The base part in words, e.g. "1회 기본급의 10% (8시간까지)". */
    fun describeBase(): String = when (basis) {
        ExtraBasis.LEGAL -> basis.label
        ExtraBasis.HOURLY_RATE -> "통상시급 × ${PayCalculator.pct(multiplier)}"
        ExtraBasis.FIXED_PER_TIME -> "1회 ${won(amount)}"
        ExtraBasis.PERCENT_OF_BASE -> "1회 기본급의 ${percent(percentOfBase)}"
        ExtraBasis.FIXED_PER_HOUR -> "시간당 ${won(amount)}"
        ExtraBasis.TIERED -> "주간 ${describeTiers(false)} · 야간 ${describeTiers(true)}"
    } + (if (perTime && coverMinutes != null) " (${PayCalculator.hours(coverMinutes)}까지)" else "")

    fun describe(): String = describeBase() +
        (if (minMinutes > 0) ", 최소 ${PayCalculator.hours(minMinutes)} 인정" else "") +
        (if (basis != ExtraBasis.LEGAL && legalPremiums) " + 연장·휴일 가산" else "") +
        (if (basis != ExtraBasis.LEGAL && !nightPremium) ", 야간 가산 없음" else "") +
        (if (bonus > 0) " + 1회 ${won(bonus)}" else "")
}

/** [EXTRA-02] What work on a day off (휴무일, not a paid holiday) counts as. Companies decide this in the 취업규칙. */
enum class RestDayWork(val label: String, val detail: String) {
    /** 근로기준법: 휴무일 work is not 휴일근로; only hours beyond 8 a day or 40 a week are 연장 (제50·56조). */
    LAW("법대로", "하루 8시간·주 40시간 안은 100%, 넘는 시간은 150%"),
    HOLIDAY("휴일근로로", "8시간까지 150%, 넘으면 200%"),
    OVERTIME("연장근로로", "모든 시간 150%"),
}

/**
 * [EXTRA-02] Which day is the 주휴일 (근로기준법 제55조 제1항): work on it is 휴일근로 under every
 * company rule. Shift rosters often name none, so the default is [Unknown] and every day off is a 휴무일.
 */
sealed interface WeeklyHoliday {
    data class Fixed(val day: DayOfWeek) : WeeklyHoliday
    /** The first day off of each week. */
    data object FirstRestDay : WeeklyHoliday
    data object Unknown : WeeklyHoliday
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
    /** [EXTRA-02] Work on a day off that is not a paid holiday; the law by default. */
    val restDayWork: RestDayWork = RestDayWork.LAW,
    /** [EXTRA-02] The 주휴일, whose work is 휴일근로 whatever [restDayWork] says. */
    val weeklyHoliday: WeeklyHoliday = WeeklyHoliday.Unknown,
    /** 공휴일·근로자의 날 work counts as holiday work (no 휴일 대체). */
    val publicHolidaysPaid: Boolean = true,
    /** [EXTRA-03] How each kind of extra work is paid, by [ExtraKind.id]; a kind not here is paid by law. */
    val extra: Map<Long, ExtraRule> = emptyMap(),
    val overtimeRounding: MinuteRounding = MinuteRounding(),
    /** 시급제 주휴수당 (근로기준법 제55조, 주 15시간 이상). */
    val weeklyHolidayPay: Boolean = true,
    /** 연차 days the company grants this year; null when not entered [FEAT-10]. */
    val annualLeaveDays: Double? = null,
    /** 연차 carried over from last year (이월), on top of [annualLeaveDays] [PAY-12][REL-08]. */
    val annualLeaveCarry: Double = 0.0,
    /**
     * A scheduled shift on a 공휴일 is paid like this kind of extra work ([ExtraKind.id]) on top of the
     * salary, e.g. 대근 방식 [PAYX-07]; null uses the legal holiday premiums.
     */
    val publicHolidayAs: Long? = null,
    /** 연차 taken on a 공휴일 does not use up the year's days (some companies) [FEAT-10]. */
    val holidayLeaveFree: Boolean = false,
    /**
     * [PAYX-11] A rule for one kind of extra work when it covers a given shift type, e.g. 대근 on
     * a 나이트 paid 1회 10만원 and on a 데이 8만원. Falls back to [extra] for other shifts.
     */
    val extraByShift: Map<Long, Map<Long, ExtraRule>> = emptyMap(),
    /**
     * [PAYX-12] The company's night window, e.g. 23:00~07:00; null uses the legal 22:00~06:00.
     * Night minutes of every shift on the calendar are counted in it, base shifts included.
     */
    val nightStart: java.time.LocalTime? = null,
    val nightEnd: java.time.LocalTime? = null,
    /** [PAYX-09] What this person's company calls each kind (e.g. 잔업 → 조출), by id; filled from settings. */
    val extraNames: Map<Long, String> = emptyMap(),
    /**
     * [PAYX-14] An overnight shift that crosses into or out of a 공휴일·주휴일 counts as holiday work
     * whole, by the day it started on. Off by default: only the minutes inside the holiday count.
     */
    val holidayByShiftStart: Boolean = false,
) {
    fun extraRule(kind: Long?): ExtraRule? = kind?.let { extra[it] }?.takeUnless { it.isLegal }

    /** The rule for [kind] when it covered a shift of type [shiftTypeId], per shift first [PAYX-11]. */
    fun extraRule(kind: Long?, shiftTypeId: Long?): ExtraRule? =
        kind?.let { k -> shiftTypeId?.let { extraByShift[k]?.get(it) }?.takeUnless { it.isLegal } } ?: extraRule(kind)

    fun extraName(kind: Long): String = extraNames[kind]?.takeIf { it.isNotBlank() } ?: when (kind) {
        ExtraKind.OVERTIME_ID -> "잔업"
        ExtraKind.SPECIAL_ID -> "특근"
        ExtraKind.COVER_ID -> "대근"
        else -> "추가 근무"
    }

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
