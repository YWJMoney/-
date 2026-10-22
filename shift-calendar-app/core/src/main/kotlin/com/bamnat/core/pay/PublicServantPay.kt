package com.bamnat.core.pay

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToLong

/**
 * [JOB-01][PAYX-01] 공무원·경찰·소방's 초과근무수당 by 공무원수당 등에 관한 규정 (research/1.21-직군-조사.md):
 * 봉급기준액 = 기준호봉 봉급액 × 55% (8급 이하 60%), then
 * 시간외근무수당 = 봉급기준액 ÷ 209 × 150% an hour (제15조), 야간근무수당 = 봉급기준액 ÷ 209 × 50% an hour
 * from 22:00 to 06:00 (제16조), 휴일근무수당 = 봉급기준액 ÷ 26 × 150% a day (제17조, 현업).
 * The 봉급 entered stands in for the 기준호봉's, so the amounts are marked 확인 필요 until the user fixes them.
 */
object PublicServantPay {
    const val RATIO = 0.55
    const val RATIO_LOW_GRADES = 0.60
    const val NOTE = "공무원은 근로기준법 가산 대신 공무원수당 규정으로 계산해요. 입력한 봉급으로 야간·휴일·시간외 수당을 " +
        "회사 규칙에 넣었어요. 기준호봉 봉급이 다르면 금액을 고쳐 주세요. 시간외는 월 57시간 상한이 있어요"
    /** The 확인 필요 key for the rules worked out here. */
    const val ESTIMATED = "public-rules"

    const val OVERTIME_ID = "public-overtime"
    const val LONGEVITY_ID = "public-longevity"
    const val LONGEVITY_EXTRA_ID = "public-longevity-extra"
    const val FESTIVAL_ID = "public-festival"

    fun isPublic(o: Occupation?) = o == Occupation.PUBLIC || o == Occupation.POLICE || o == Occupation.FIRE

    fun standardBase(monthlyPay: Long, lowGrade: Boolean = false): Long =
        (monthlyPay * if (lowGrade) RATIO_LOW_GRADES else RATIO).roundToLong()

    fun overtimePerHour(standard: Long): Long = (standard / 209.0 * 1.5).roundToLong()
    fun nightPerHour(standard: Long): Long = (standard / 209.0 * 0.5).roundToLong()
    fun holidayPerDay(standard: Long): Long = (standard / 26.0 * 1.5).roundToLong()

    /** The three 수당 as the user's own rules: night hours of every shift, a worked holiday, and extra hours. */
    fun rules(standard: Long): List<CustomRule> = listOf(
        CustomRule("public-night", "야간근무수당", RuleWhen(from = LocalTime.of(22, 0), to = LocalTime.of(6, 0)),
            RulePay.PerHour(nightPerHour(standard)), source = "공무원수당 등에 관한 규정 제16조: 봉급기준액 ÷ 209 × 50%"),
        CustomRule("public-holiday", "휴일근무수당", RuleWhen(dayKind = RuleDayKind.HOLIDAY, pieces = RulePieces.SHIFT),
            RulePay.PerTime(holidayPerDay(standard)), source = "제17조: 봉급기준액 ÷ 26 × 150% (현업)"),
        CustomRule(OVERTIME_ID, "시간외근무수당", RuleWhen(pieces = RulePieces.EXTRA),
            RulePay.PerHour(overtimePerHour(standard)), source = "제15조: 봉급기준액 ÷ 209 × 150%, 월 57시간 상한"),
    )

    /**
     * [JOB-02] [p] with the 봉급표 계급·호봉 [g] picked on [on]: the 봉급 from the table from that month, the three
     * 수당 rules from the 기준호봉's 봉급기준액 (replacing the ones worked out from a typed 봉급), and the regular
     * 수당 as allowances: 정근수당 (1·7월), 정근수당 가산금 (매달) and 명절휴가비 (설·추석). Other items stay.
     */
    fun apply(p: PayProfile, g: PublicGrade, on: LocalDate): PayProfile {
        val grade = PublicPayTable.grade(g.gradeId) ?: return p
        val salary = PublicPayTable.salary(grade, g.step, on) ?: return p
        val from = on.withDayOfMonth(1)
        val years = PublicPayTable.years(g.serviceStart, on)
        val own = setOf("public-night", "public-holiday", OVERTIME_ID)
        val ids = setOf(LONGEVITY_ID, LONGEVITY_EXTRA_ID, FESTIVAL_ID)
        fun fixed(v: Long) = ValueTimeline(listOf(DatedValue(from, v)))
        val items = listOf(
            PayItem(LONGEVITY_ID, "정근수당", fixed(0), Cadence.BONUS, months = setOf(1, 7),
                percentOfBase = PublicPayTable.longevityRate(years)),
            PayItem(LONGEVITY_EXTRA_ID, "정근수당 가산금", fixed(PublicPayTable.longevityExtra(years)), Cadence.MONTHLY),
            PayItem(FESTIVAL_ID, "명절휴가비", fixed(0), Cadence.BONUS, festivals = setOf(Festival.SEOLLAL, Festival.CHUSEOK),
                percentOfBase = PublicPayTable.FESTIVAL_SHARE),
        )
        return p.copy(
            base = p.base.with(DatedValue(from, salary)),
            customRules = p.customRules.filterNot { it.id in own } + rules(PublicPayTable.standardBase(grade, on)),
            items = p.items.filterNot { it.id in ids } + items,
            estimatedFields = p.estimatedFields - ESTIMATED - Unknowns.BASE,  // the 봉급 now comes from the table
            rules = p.rules.copy(overtimePremium = 0.0, nightPremium = 0.0, holidayPremiumWithin8h = 0.0, holidayPremiumOver8h = 0.0),
            publicGrade = g,
        )
    }
}

/**
 * [JOB-02] A public servant's place in the 봉급표: [gradeId] is [PublicPayTable.Grade.id], [serviceStart] the first day
 * of service for 정근수당, and [onSite] whether they are 현업·교대 (no 57-hour cap, no 1-hour deduction, 제15조).
 */
data class PublicGrade(val gradeId: String, val step: Int, val serviceStart: LocalDate? = null, val onSite: Boolean = true)
