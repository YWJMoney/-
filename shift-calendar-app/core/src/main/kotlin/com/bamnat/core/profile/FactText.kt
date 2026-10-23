package com.bamnat.core.profile

import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.pay.WageType
import com.bamnat.core.work.WorkStyle

/**
 * [EASY-08] A fact as the profile summary says it, in a few words. What is long or private (기본급, 수당 목록,
 * 회사 규칙, 작년 급여) says only that it is there; its own screen shows it.
 */
object FactText {
    const val NOT_KNOWN = "아직 몰라요"
    private const val SET = "정해 둠"

    fun of(fact: Fact<*>): String {
        val v = fact.value ?: return NOT_KNOWN
        return when (fact.key) {
            FactKey.WORK_STYLE -> (v as? WorkStyle)?.label ?: SET
            FactKey.SHIFTS -> (v as? List<*>)?.let { "근무 ${it.size}종류" } ?: SET
            FactKey.ROTATION -> if (v == "monthly") "달마다 근무표" else SET
            FactKey.EXTRA_KINDS -> (v as? Collection<*>)?.let { if (it.isEmpty()) "없음" else "${it.size}종류" } ?: SET
            FactKey.OVERTIME_CAP -> "${v}시간"
            FactKey.ALARM_LEAD -> "${v}분 전"
            FactKey.WAGE_TYPE -> when (v) { WageType.MONTHLY -> "월급"; WageType.HOURLY -> "시급"; WageType.DAILY -> "일당"; else -> SET }
            FactKey.PAY_ITEMS -> (v as? Collection<*>)?.let { if (it.isEmpty()) "없음" else "${it.size}개" } ?: SET
            FactKey.PAY_DAY -> "매달 ${v}일"
            FactKey.MONTHLY_HOURS -> "${v}시간"
            FactKey.DAILY_HOURS -> (v as? Int)?.let { hours(it) } ?: SET
            FactKey.HOLIDAY_PAY -> when (v) { HolidayPayMode.FULL -> "하루치 + 가산"; HolidayPayMode.PREMIUM_ONLY -> "가산분만"; else -> SET }
            FactKey.FIVE_OR_MORE -> if (v == true) "5명 이상" else "4명 이하"
            FactKey.HIRE_DATE -> v.toString()
            FactKey.TAX_MODE -> when (v) { TaxMode.INSURANCE_4 -> "4대보험"; TaxMode.WITHHOLDING_3_3 -> "3.3%"; TaxMode.NONE -> "안 떼요"; else -> SET }
            FactKey.TAX_FAMILY -> ((v as? Pair<*, *>)?.first ?: v).let { "${it}명" }
            FactKey.SLEEP_TARGET -> (v as? Int)?.let { hours(it) } ?: SET
            FactKey.COMMUTE -> "${v}분"
            else -> SET
        }
    }

    /** [EASY-08] The value as the edit sheet's chips and numbers write it ([Questions.of]), to start from what is there. */
    fun answerOf(fact: Fact<*>): String? {
        val v = fact.value ?: return null
        return when (fact.key) {
            FactKey.WAGE_TYPE, FactKey.TAX_MODE, FactKey.HOLIDAY_PAY -> (v as? Enum<*>)?.name
            FactKey.DAILY_HOURS -> (v as? Int)?.let { "${it / 60}" }
            FactKey.TAX_FAMILY -> ((v as? Pair<*, *>)?.first ?: v).toString()
            FactKey.OVERTIME_CAP, FactKey.ALARM_LEAD, FactKey.PAY_DAY, FactKey.MONTHLY_HOURS, FactKey.SLEEP_TARGET,
            FactKey.COMMUTE, FactKey.FIVE_OR_MORE, FactKey.HIRE_DATE -> v.toString()
            else -> null
        }
    }

    private fun hours(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60}시간" else "${minutes / 60}시간 ${minutes % 60}분"

    /** "급여 계산·연차 원장·알람 점검 3곳에 바로 적용돼요": what a change reaches, from the features that read it. */
    fun applies(key: FactKey): String {
        val names = key.usedBy.mapNotNull { FeatureRegistry.get(it)?.name }.distinct()
        if (names.isEmpty()) return "바꾸면 바로 적용돼요"
        val shown = names.take(3).joinToString("·")
        return if (names.size == 1) "${shown}에 바로 적용돼요" else "$shown${if (names.size > 3) " 등" else ""} ${names.size}곳에 바로 적용돼요"
    }

    /** [EASY-09] The facts [feature] waits for that fit this calendar, in the catalog's order. */
    fun waiting(facts: Map<FactKey, Fact<*>>, feature: String): List<FactKey> =
        FeatureNeeds.of(feature).filter { it in facts && Needs.isMissing(facts[it]) }.sortedBy { it.ordinal }

    /**
     * [EASY-09] "이걸 알려 주면 시작해요 · 급여일·기본급": the facts a feature that is on still waits for, for its
     * line on the 기능 켜고 끄기 screen. Null when it has all it needs.
     */
    fun startsWith(facts: Map<FactKey, Fact<*>>, feature: String): String? {
        val keys = waiting(facts, feature)
        if (keys.isEmpty()) return null
        return "이걸 알려 주면 시작해요 · " + keys.take(3).joinToString("·") { it.label } + if (keys.size > 3) " 외 ${keys.size - 3}개" else ""
    }
}
