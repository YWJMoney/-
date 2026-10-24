package com.bamnat.core.pay

import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * [PAYX-16] 포괄임금·탄력적 근로시간제 예시 규칙. The app does not decide whether a company's 포괄임금 약정 or 탄력제
 * is valid (that needs the contract, the 취업규칙 and a 서면 합의); it gives ready examples the user fills from
 * their own papers, each with what it does and where the law is (근로기준법 제51조, 제56조).
 */
object PayExamples {
    enum class Kind { FIXED_OVERTIME, INCLUSIVE_TOTAL, FLEX_2W, FLEX_1M, FLEX_3M }

    data class Example(val kind: Kind, val title: String, val summary: String, val guide: String)

    val all: List<Example> = listOf(
        Example(Kind.FIXED_OVERTIME, "포괄임금 (고정 연장수당)",
            "매달 정해진 연장수당에 연장 20시간이 들어 있다고 보는 예시예요",
            "계약서에 적힌 '고정 연장 N시간·금액'을 넣어요. 앱은 그 수당을 연장 가산을 미리 준 돈으로 보고, 실제 연장이 " +
                "더 많으면 모자란 만큼을 따로 보여 줘요. 포괄임금이 유효한지는 앱이 판단하지 않아요(근로기준법 제56조)."),
        Example(Kind.INCLUSIVE_TOTAL, "포괄 월급 (금액이 안 나뉜 경우)",
            "월급 한 덩어리에 연장 N시간이 들어 있다고만 적힌 계약을 기본급과 고정 연장수당으로 나눠 보는 예시예요",
            "기본급 = 월급 × 209 ÷ (209 + 1.5 × N)으로 거꾸로 나눠요. 계약서에 기본급이 따로 적혀 있지 않을 때만 쓰고, 나눈 금액은 " +
                "'추정'으로 보여요. 이렇게 나눈 시급이 최저임금보다 낮으면 그 약정은 효력이 없어요(근로기준법 제56조, 대법원 2024.12.26. 2020다300299)."),
        Example(Kind.FLEX_2W, "탄력적 근로시간제 2주",
            "2주를 평균해 주 40시간을 넘는 시간만 연장으로 봐요. 한 주는 48시간까지예요",
            "취업규칙에 2주 단위로 정한 회사예요(근로기준법 제51조 제1항). 단위기간이 시작하는 날을 넣어 주세요."),
        Example(Kind.FLEX_1M, "탄력적 근로시간제 1개월",
            "매달 1일~말일을 평균해 주 40시간을 넘는 시간만 연장으로 봐요",
            "근로자대표와 서면 합의가 있어야 해요(근로기준법 제51조 제2항). 한 주 52시간, 하루 12시간까지예요."),
        Example(Kind.FLEX_3M, "탄력적 근로시간제 3개월",
            "1·4·7·10월부터 3개월을 평균해 주 40시간을 넘는 시간만 연장으로 봐요",
            "근로자대표와 서면 합의가 있어야 해요(근로기준법 제51조 제2항). 한 주 52시간, 하루 12시간까지예요."),
    )

    /** [PAYX-26] The id of the 고정 연장수당 [inclusiveSplit] makes. */
    const val INCLUSIVE_ID = "example-inclusive-ot"

    /**
     * [PAYX-26] 금액 미분리 포괄 월급 T with N hours of 연장 in it: 기본급 B = T × H ÷ (H + 1.5N), H being 월 소정근로시간, so
     * B ÷ H × 1.5 × N is the rest. Both parts are marked 추정; a monthly salary only. The monthly salary is the base on [from].
     */
    fun inclusiveSplit(profile: PayProfile, from: LocalDate, overtimeHours: Int): PayProfile {
        if (profile.wageType != WageType.MONTHLY || overtimeHours <= 0) return profile
        val total = profile.base.on(java.time.YearMonth.from(from)).value
        val h = profile.monthlyHours.coerceAtLeast(1).toDouble()
        val base = (total * h / (h + 1.5 * overtimeHours)).roundToLong()
        return profile.copy(
            base = profile.base.copy(entries = profile.base.entries.filter { it.from.isBefore(from) } + DatedValue(from, base)),
            items = profile.items.filterNot { it.id == INCLUSIVE_ID } + PayItem(
                INCLUSIVE_ID, "고정 연장수당 (포괄에서 나눔)", ValueTimeline(listOf(DatedValue(from, total - base))), Cadence.MONTHLY,
                fixedOvertimeMinutes = overtimeHours * 60,
            ),
            estimatedFields = profile.estimatedFields + Unknowns.BASE + INCLUSIVE_ID,
        )
    }

    fun of(kind: Kind): Example = all.first { it.kind == kind }

    /**
     * [profile] with [kind] put in, from [from]. A 고정 연장수당 of [overtimeHours] hours is paid at 통상시급 × 150%
     * when [amount] is not given; the user changes it to what the contract says.
     */
    fun apply(kind: Kind, profile: PayProfile, from: LocalDate, overtimeHours: Int = 20, amount: Long? = null, flexStart: LocalDate? = null): PayProfile =
        when (kind) {
            Kind.FIXED_OVERTIME -> {
                val month = java.time.YearMonth.from(from)
                val hourly = when (profile.wageType) {
                    WageType.MONTHLY -> PayCalculator.ordinaryWage(profile, month).total.toDouble() / profile.monthlyHours.coerceAtLeast(1)
                    WageType.HOURLY -> profile.base.on(month).value.toDouble()
                    WageType.DAILY -> profile.base.on(month).value * 60.0 / profile.dailyHours.coerceIn(1, 480)
                }
                val pay = amount ?: (hourly * 1.5 * overtimeHours).roundToLong() / 10 * 10
                val id = "example-fixed-ot"
                profile.copy(items = profile.items.filterNot { it.id == id } + PayItem(
                    id, "고정 연장수당", ValueTimeline(listOf(DatedValue(from, pay))), Cadence.MONTHLY,
                    fixedOvertimeMinutes = overtimeHours * 60,
                ))
            }
            Kind.INCLUSIVE_TOTAL -> inclusiveSplit(profile, from, overtimeHours)
            Kind.FLEX_2W -> profile.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.TWO_WEEKS, flexStart = flexStart ?: from)
            Kind.FLEX_1M -> profile.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.MONTH)
            Kind.FLEX_3M -> profile.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.QUARTER)
        }
}
