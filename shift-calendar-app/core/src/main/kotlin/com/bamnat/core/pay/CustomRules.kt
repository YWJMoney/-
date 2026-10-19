package com.bamnat.core.pay

import java.time.DayOfWeek
import java.time.LocalTime

/** [PAYS-06] Which days a [CustomRule] looks at. */
enum class RuleDayKind(val label: String) {
    ANY("모든 날"),
    /** A day with a shift on the roster. */
    WORK_DAY("근무일"),
    /** A day with no shift and no leave (휴무일·휴일 alike). */
    OFF_DAY("쉬는 날"),
    /** A paid holiday as the month counts it: 공휴일, 근로자의 날, the 주휴일. */
    HOLIDAY("공휴일·휴일"),
}

/** [PAYS-06] Which pieces of a day's work a [CustomRule] counts. */
enum class RulePieces(val label: String) {
    ALL("근무와 추가 근무"),
    SHIFT("근무표 근무만"),
    EXTRA("잔업·특근·대근만"),
}

/**
 * [PAYS-06] The work a [CustomRule] pays for. Every condition left empty matches everything, so the empty
 * [RuleWhen] is every minute worked. Work belongs to the day it started on, as for the rest of the month.
 */
data class RuleWhen(
    val dayKind: RuleDayKind = RuleDayKind.ANY,
    /** Days of the week the work starts on; empty is every day. */
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    /** Shift types (the day's own, or the one a piece of extra work covered); empty is any. */
    val shiftTypeIds: Set<Long> = emptySet(),
    val pieces: RulePieces = RulePieces.ALL,
    /** Kinds of extra work ([com.bamnat.core.model.ExtraKind.id]) when [pieces] takes extra work; empty is any. */
    val extraKinds: Set<Long> = emptySet(),
    /** Only the minutes between two clock times; an end at or before the start runs past midnight. */
    val from: LocalTime? = null,
    val to: LocalTime? = null,
    /** Only a day's minutes beyond this many, e.g. 12시간 넘는 시간; 0 is all of them. */
    val overMinutes: Int = 0,
    /** For [RulePay.PerTime]: a day counts only with at least this many matching minutes, e.g. 8시간 이상 근무한 날. */
    val minMinutes: Int = 0,
) {
    val hasWindow: Boolean get() = from != null && to != null && from != to

    /** "토요일 근무일 19~23시", "" when it matches every minute. */
    fun describe(shiftNames: Map<Long, String> = emptyMap(), extraNames: Map<Long, String> = emptyMap()): String = buildList {
        if (daysOfWeek.isNotEmpty()) add(daysOfWeek.sorted().joinToString("·") { DAY_NAMES.getValue(it) } + "요일")
        if (dayKind != RuleDayKind.ANY) add(dayKind.label)
        if (shiftTypeIds.isNotEmpty()) add(shiftTypeIds.joinToString("·") { shiftNames[it] ?: "근무" })
        when (pieces) {
            RulePieces.ALL -> Unit
            RulePieces.SHIFT -> add("근무표 근무")
            RulePieces.EXTRA -> add(if (extraKinds.isEmpty()) "추가 근무" else extraKinds.joinToString("·") { extraNames[it] ?: "추가 근무" })
        }
        if (hasWindow) add("${clock(from!!)}~${clock(to!!)}시")
        if (overMinutes > 0) add("${PayCalculator.hours(overMinutes)} 넘는 시간")
        if (minMinutes > 0) add("${PayCalculator.hours(minMinutes)} 이상 일한 날")
    }.joinToString(" ")

    companion object {
        val DAY_NAMES: Map<DayOfWeek, String> = mapOf(
            DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화", DayOfWeek.WEDNESDAY to "수", DayOfWeek.THURSDAY to "목",
            DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토", DayOfWeek.SUNDAY to "일",
        )

        fun clock(t: LocalTime): String = if (t.minute == 0) "%02d".format(t.hour) else "%02d:%02d".format(t.hour, t.minute)
    }
}

/** [PAYS-06] How a [CustomRule] pays for the work it matched. */
sealed interface RulePay {
    /** [amount] won for every hour matched, e.g. 시간당 2,000원. */
    data class PerHour(val amount: Long) : RulePay

    /** 통상시급 × [rate] for every hour matched, e.g. 0.5 for a 50% premium on top. */
    data class OfHourly(val rate: Double) : RulePay

    /** 기본급 × [share] for every [perMinutes] matched, in proportion, e.g. 8시간에 기본급의 10%. */
    data class OfBase(val share: Double, val perMinutes: Int = 8 * 60) : RulePay

    /** [amount] won once for each day with matching work, e.g. 토요일 근무 1회 20,000원. */
    data class PerTime(val amount: Long) : RulePay

    /**
     * [PAYS-07] [amount] won once a month, whatever the month's work, e.g. 자격수당 매달 5만원, 기숙사비 매달
     * 5만원 차감. The rule's [RuleWhen] is not looked at.
     */
    data class Monthly(val amount: Long) : RulePay

    /** [PAYS-07] 기본급 × [share] once a month, e.g. 근속수당 기본급의 5%. The rule's [RuleWhen] is not looked at. */
    data class OfBaseMonthly(val share: Double) : RulePay

    fun describe(): String = when (this) {
        is PerHour -> "시간당 ${won(amount)}"
        is OfHourly -> "통상시급의 ${ExtraRule.percent(rate)}"
        is OfBase -> "${PayCalculator.hours(perMinutes)}에 기본급의 ${ExtraRule.percent(share)}"
        is PerTime -> "1회 ${won(amount)}"
        is Monthly -> "매달 ${won(amount)}"
        is OfBaseMonthly -> "매달 기본급의 ${ExtraRule.percent(share)}"
    }

    /** True when the amount follows 통상시급 or 기본급, so it is only as sure as they are. */
    val followsWage: Boolean get() = this is OfHourly || this is OfBase || this is OfBaseMonthly

    /** [PAYS-07] Paid once a month rather than on the work the rule matches. */
    val monthly: Boolean get() = this is Monthly || this is OfBaseMonthly
}

/**
 * [PAYS-06] A pay rule the user states in their own words, for anything the presets and the law do not cover:
 * "토요일 근무는 시간당 50% 더", "19~23시 근무 25% 가산", "일요일 근무 1회 2만원", "12시간 넘는 시간은 시간당
 * 3,000원". It is paid on top of everything else, on the minutes [whenever] matches, as its own line, and is
 * never 통상임금. The rule is the company's: nothing the law gives is taken away for it.
 */
data class CustomRule(
    val id: String,
    val name: String,
    val whenever: RuleWhen,
    val pay: RulePay,
    val on: Boolean = true,
    /** The sentence the rule was read from, to show next to it; empty for a rule made with the editor. */
    val source: String = "",
    /**
     * [PAYS-07] Taken off the month instead of added, e.g. 지각 1회 10,000원 차감, 기숙사비 매달 50,000원 차감. The
     * month's total is what is paid before tax, so this is for money the payslip takes off its 지급 side.
     */
    val deduct: Boolean = false,
) {
    /** "토요일 근무일: 통상시급의 50%", "매달 50,000원 차감". */
    fun describe(shiftNames: Map<Long, String> = emptyMap(), extraNames: Map<Long, String> = emptyMap()): String =
        (if (pay.monthly) "" else whenever.describe(shiftNames, extraNames).let { w -> if (w.isEmpty()) "모든 근무: " else "$w: " }) +
            pay.describe() + if (deduct) " 차감" else ""

    /**
     * [PAYS-09] The rule in words with no amount in won, for telling the developer the company's way: "토요일: 1회
     * 정액", "매달 기본급의 5%", "매달 정액 차감". Shares of 시급 and 기본급 stay; they are the company's, not the user's.
     */
    fun describeWithoutAmount(shiftNames: Map<Long, String> = emptyMap(), extraNames: Map<Long, String> = emptyMap()): String {
        val way = when (val p = pay) {
            is RulePay.PerHour -> "시간당 정액"
            is RulePay.PerTime -> "1회 정액"
            is RulePay.Monthly -> "매달 정액"
            else -> p.describe()
        }
        return (if (pay.monthly) "" else whenever.describe(shiftNames, extraNames).let { w -> if (w.isEmpty()) "모든 근무: " else "$w: " }) +
            way + if (deduct) " 차감" else ""
    }

    /** [PAYS-07] The month's line for it: "자격수당 (회사 규칙)", "기숙사비 (차감)". */
    val lineLabel: String get() = "$name (${if (deduct) "차감" else "회사 규칙"})"

    companion object {
        const val MAX = 20
        const val MAX_NAME = 16
    }
}
