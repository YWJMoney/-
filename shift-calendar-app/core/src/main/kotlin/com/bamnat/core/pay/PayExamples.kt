package com.bamnat.core.pay

import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * [PAYX-16] 포괄임금·탄력적 근로시간제 예시 규칙. The app does not decide whether a company's 포괄임금 약정 or 탄력제
 * is valid (that needs the contract, the 취업규칙 and a 서면 합의); it gives ready examples the user fills from
 * their own papers, each with what it does and where the law is (근로기준법 제51조, 제56조).
 */
object PayExamples {
    enum class Kind { FIXED_OVERTIME, FLEX_2W, FLEX_1M, FLEX_3M }

    data class Example(val kind: Kind, val title: String, val summary: String, val guide: String)

    val all: List<Example> = listOf(
        Example(Kind.FIXED_OVERTIME, "포괄임금 (고정 연장수당)",
            "매달 정해진 연장수당에 연장 20시간이 들어 있다고 보는 예시예요",
            "계약서에 적힌 '고정 연장 N시간·금액'을 넣어요. 앱은 그 수당을 연장 가산을 미리 준 돈으로 보고, 실제 연장이 " +
                "더 많으면 모자란 만큼을 따로 보여 줘요. 포괄임금이 유효한지는 앱이 판단하지 않아요(근로기준법 제56조)."),
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
            Kind.FLEX_2W -> profile.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.TWO_WEEKS, flexStart = flexStart ?: from)
            Kind.FLEX_1M -> profile.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.MONTH)
            Kind.FLEX_3M -> profile.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.QUARTER)
        }
}
