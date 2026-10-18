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
    /**
     * [EXTRA-08] An hour's pay ([ExtraRule.unit]) and each premium the company adds on it, ticked one
     * by one with its own rate, e.g. 8시간에 기본급 10% + 8시간 넘으면 50% + 야간 50%.
     */
    UNIT("단가와 가산"),
}

/** [EXTRA-08] What one hour of the work is paid at, before premiums. */
enum class PayUnit(val label: String) {
    /** 통상시급, as the law pays. */
    ORDINARY("통상시급"),
    /** 기본급 × [ExtraRule.percentOfBase] for [ExtraRule.unitMinutes] of work, e.g. 8시간에 기본급의 10%. */
    BASE_SHARE("기본급 %"),
    /** [ExtraRule.amount] won an hour. */
    PER_HOUR("시간당 금액"),
    /**
     * [ExtraRule.amount] won each time for [ExtraRule.coverMinutes] of work (every hour when null);
     * hours beyond it and the premiums are paid on 통상시급.
     */
    PER_TIME("1회 금액"),
}

/** [EXTRA-08] The minutes of a piece of work a premium is paid on. */
sealed interface PremiumScope {
    /** Every minute worked. */
    data object All : PremiumScope
    /** Minutes of this work beyond [minutes], e.g. 대근 8시간 넘는 시간. */
    data class Over(val minutes: Int) : PremiumScope
    /** What the law counts as 연장: beyond 8 hours a day or 40 a week (제53조), the company's day-off rule applied. */
    data object LegalOvertime : PremiumScope
    /** Minutes in the night window, the company's or 22~06 (제56조 제3항). */
    data object Night : PremiumScope
    /** Minutes between two clock times, e.g. 19:00~23:00; an end at or before the start runs past midnight. */
    data class Window(val from: java.time.LocalTime, val to: java.time.LocalTime) : PremiumScope
    /** Minutes on a 공휴일·주휴일·근로자의 날 (휴일근로, 제56조 제2항). */
    data object Holiday : PremiumScope
    /** Minutes on a day off that is not a paid holiday (휴무일): no premium by law, some companies pay one. */
    data object RestDay : PremiumScope
}

/**
 * [EXTRA-08] One premium on top of the hour, ticked on or off with its own rate: the four the law
 * knows (연장, 야간, 휴일, and 휴무일 that it does not) are always listed; companies add their own,
 * e.g. 심야 19~23시 25%.
 */
data class Premium(
    /** [OVERTIME], [NIGHT], [HOLIDAY], [REST] for the four always listed; "c1", "c2" … for added ones. */
    val key: String,
    val name: String,
    /** On top of 100%: 0.5 = 50%. */
    val rate: Double,
    val on: Boolean = true,
    val scope: PremiumScope,
    /** Only on a day off or a paid holiday, e.g. 쉬는 날 심야 가산. */
    val offDaysOnly: Boolean = false,
) {
    val custom: Boolean get() = key !in BUILT_IN

    companion object {
        const val OVERTIME = "overtime"
        const val NIGHT = "night"
        const val HOLIDAY = "holiday"
        const val REST = "rest"
        val BUILT_IN = listOf(OVERTIME, NIGHT, HOLIDAY, REST)
        const val MAX_CUSTOM = 6
        const val MAX_RATE = 3.0
        const val MAX_NAME = 12

        /** One of the four always listed, by [key], at [rate] (50% when unset) and ticked when [on]. */
        fun builtIn(key: String, rate: Double = 0.5, on: Boolean = false): Premium = when (key) {
            OVERTIME -> Premium(OVERTIME, "연장 가산", rate, on, PremiumScope.LegalOvertime)
            NIGHT -> Premium(NIGHT, "야간 가산", rate, on, PremiumScope.Night)
            HOLIDAY -> Premium(HOLIDAY, "휴일 가산", rate, on, PremiumScope.Holiday)
            else -> Premium(REST, "휴무일 가산", rate, on, PremiumScope.RestDay)
        }
    }
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
    /** [EXTRA-08] For [ExtraBasis.UNIT]: what an hour is paid at. */
    val unit: PayUnit = PayUnit.ORDINARY,
    /** [EXTRA-08] For [PayUnit.BASE_SHARE]: the minutes [percentOfBase] of 기본급 pays for, e.g. 8시간. */
    val unitMinutes: Int = 8 * 60,
    /** [EXTRA-08] For [ExtraBasis.UNIT]: the premiums, the four always listed first, then the company's own. */
    val premiums: List<Premium> = emptyList(),
) {
    /** By law with nothing on top: the same as having no rule. */
    val isLegal: Boolean get() = basis == ExtraBasis.LEGAL && bonus <= 0

    /** A payment each time that stands for some hours of work. */
    val perTime: Boolean get() = basis == ExtraBasis.FIXED_PER_TIME || basis == ExtraBasis.PERCENT_OF_BASE

    /** [EXTRA-08] The rule pays the night minutes itself, so they leave the month's night line. */
    val ownsNight: Boolean get() = basis == ExtraBasis.UNIT || !nightPremium

    fun premium(key: String): Premium? = premiums.firstOrNull { it.key == key }

    /** [EXTRA-08] The premiums that count, in the order they are paid. */
    val ticked: List<Premium> get() = premiums.filter { it.on && it.rate > 0 }

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

        /**
         * [EXTRA-08] The ticks that say what the law pays, to start the company's own way from:
         * 연장, 야간 and 휴일 at the premiums in force ([overtime], [night], [holiday]; 0 under 5
         * employees, shown unticked at 50%), and 휴무일 only when the company pays a day off as a holiday.
         */
        fun lawUnit(overtime: Double, night: Double, holiday: Double, restAsHoliday: Boolean, bonus: Long = 0, minMinutes: Int = 0): ExtraRule {
            fun rate(p: Double) = if (p > 0) p else 0.5
            return ExtraRule(
                basis = ExtraBasis.UNIT,
                bonus = bonus,
                minMinutes = minMinutes,
                premiums = listOf(
                    Premium.builtIn(Premium.OVERTIME, rate(overtime), overtime > 0),
                    Premium.builtIn(Premium.NIGHT, rate(night), night > 0),
                    Premium.builtIn(Premium.HOLIDAY, rate(holiday), holiday > 0),
                    Premium.builtIn(Premium.REST, rate(holiday), restAsHoliday && holiday > 0),
                ),
            )
        }

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
        ExtraBasis.UNIT -> describeUnit()
    } + (if (perTime && coverMinutes != null) " (${PayCalculator.hours(coverMinutes)}까지)" else "")

    /** [EXTRA-08] "8시간에 기본급의 10%", "통상시급", "1회 80,000원 (8시간까지)". */
    fun describeUnit(): String = when (unit) {
        PayUnit.ORDINARY -> "통상시급"
        PayUnit.BASE_SHARE -> "${PayCalculator.hours(unitMinutes)}에 기본급의 ${percent(percentOfBase)}"
        PayUnit.PER_HOUR -> "시간당 ${won(amount)}"
        PayUnit.PER_TIME -> "1회 ${won(amount)}" + (coverMinutes?.let { " (${PayCalculator.hours(it)}까지)" } ?: "")
    }

    fun describe(): String = describeBase() +
        (if (basis == ExtraBasis.UNIT) ticked.joinToString("") { " + ${it.name.removeSuffix(" 가산")} ${percent(it.rate)}" } else "") +
        (if (minMinutes > 0) ", 최소 ${PayCalculator.hours(minMinutes)} 인정" else "") +
        (if (basis != ExtraBasis.LEGAL && basis != ExtraBasis.UNIT && legalPremiums) " + 연장·휴일 가산" else "") +
        (if (basis != ExtraBasis.LEGAL && basis != ExtraBasis.UNIT && !nightPremium) ", 야간 가산 없음" else "") +
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

/** [PAYS-03] How a company treats break time (휴게시간). */
enum class BreakPay(val label: String) {
    /** 근로기준법 제54조: free time, neither paid nor work. */
    UNPAID("무급"),
    /** Paid at 100% but still free time, so it never makes 연장, 야간 or 주휴 hours. */
    PAID("유급"),
    /**
     * Not free in practice (대기시간 under the employer's direction, 제50조 제3항): counted as work before
     * anything is sorted, so it can make 연장, 야간 and 주휴 hours.
     */
    AS_WORK("근무시간"),
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
    /**
     * [PAYS-03] 감시·단속적 근로자 승인 from 고용노동부 (근로기준법 제63조 제3호): no 연장 from the 8시간·40시간
     * limits, no legal 연장·휴일 premiums (the company's own rates still apply), no 주휴 and no paid 공휴일.
     * The night premium (5인 이상) and 연차 stay.
     */
    val supervisoryApproved: Boolean = false,
    /**
     * [PAYS-03] A workplace under 5 employees that still gives 공휴일 as paid holidays, which the law does not
     * require there (시행령 별표1). [publicHolidaysPaid] stays the 5인 이상 setting.
     */
    val holidaysUnderFive: Boolean = false,
    /** [PAYS-03] How breaks are treated; unpaid, as the law has it, by default. */
    val breakPay: BreakPay = BreakPay.UNPAID,
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
