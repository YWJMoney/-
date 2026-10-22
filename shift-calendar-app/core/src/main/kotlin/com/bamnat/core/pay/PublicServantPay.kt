package com.bamnat.core.pay

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
        CustomRule("public-overtime", "시간외근무수당", RuleWhen(pieces = RulePieces.EXTRA),
            RulePay.PerHour(overtimePerHour(standard)), source = "제15조: 봉급기준액 ÷ 209 × 150%, 월 57시간 상한"),
    )
}
