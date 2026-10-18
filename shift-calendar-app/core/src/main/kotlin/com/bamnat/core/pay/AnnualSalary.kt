package com.bamnat.core.pay

import kotlin.math.roundToLong

/**
 * [PAYS-03] 연봉 as the contract states it, to work out the monthly 기본급 the engine runs on. 연봉 is a
 * way of stating pay, not of calculating it: the month is still worked out from the 기본급 ([WageType.MONTHLY])
 * and the allowances entered beside it. Stored as [PayProfile.annual] to show how the 기본급 was reached;
 * kept on the phone only.
 */
data class AnnualPay(
    /** 연봉 총액 in won. */
    val yearly: Long,
    /** 12, or 13 when a 13th share is set aside as 퇴직금 (13분할); any other value counts as 12. */
    val split: Int = 12,
    /** 고정 연장수당 a month inside the 연봉, entered as its own allowance. */
    val fixedOvertimeMonthly: Long = 0,
    /**
     * 정기 상여 a year inside the 연봉, entered as an ordinary [Cadence.BONUS] item, since a bonus paid
     * regularly on fixed terms is 통상임금 (see [PayItem.bonusMonthlyShare]).
     */
    val regularBonusYearly: Long = 0,
    /** Other fixed monthly allowances inside the 연봉 (식대 등), entered as their own allowances. */
    val otherMonthly: Long = 0,
) {
    /** What the 연봉 is divided by: 13 for 13분할, else 12. */
    val divisor: Int get() = if (split == 13) 13 else 12

    /**
     * The monthly 기본급: (연봉 − 정기 상여) ÷ [divisor] is the month's share, less the fixed monthly allowances
     * paid as their own lines. For 12 this is (연봉 − 고정 연장수당 × 12 − 정기 상여) ÷ 12.
     */
    fun breakdown(): AnnualBreakdown {
        val lines = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val rest = yearly - regularBonusYearly.coerceAtLeast(0)
        if (regularBonusYearly > 0) lines += "연봉 ${won(yearly)} − 정기 상여 ${won(regularBonusYearly)} = ${won(rest)}"
        val share = (rest.toDouble() / divisor).roundToLong()
        val fixed = listOf("고정 연장수당" to fixedOvertimeMonthly, "기타 고정 수당" to otherMonthly).filter { it.second > 0 }
        val start = if (regularBonusYearly > 0) won(rest) else "연봉 ${won(yearly)}"
        if (fixed.isEmpty()) {
            lines += "$start ÷ $divisor = 기본급 ${won(share)}"
        } else {
            lines += "$start ÷ $divisor = 월 ${won(share)}"
            val left = share - fixed.sumOf { it.second }
            lines += "월 ${won(share)} − " + fixed.joinToString(" − ") { (name, v) -> "$name ${won(v)}" } + " = 기본급 ${won(left.coerceAtLeast(0))}"
            if (left < 0) warnings += "고정 수당이 월 몫보다 커요. 연봉과 수당 금액을 확인해 주세요"
        }
        if (divisor == 13) {
            // verify: 퇴직금 split into monthly pay is generally held invalid; no ruling is cited until checked.
            warnings += "연봉을 13으로 나눴어요. 13번째 몫이 퇴직금이라면, 퇴직금을 월급에 나눠 넣어 주는 약정은 " +
                "일반적으로 효력이 없어서 퇴직할 때 퇴직금을 따로 받을 수 있어요. 노무사나 고용노동부(1350)에 확인해 보세요"
        }
        val base = (share - fixed.sumOf { it.second }).coerceAtLeast(0)
        return AnnualBreakdown(base, share, lines, warnings)
    }
}

/** [PAYS-03] The monthly 기본급 worked out from an [AnnualPay], the steps in words and anything to check. */
data class AnnualBreakdown(
    /** The 기본급 to enter as [PayProfile.base]. */
    val monthlyBase: Long,
    /** The month's share of the 연봉 before the fixed monthly allowances come off. */
    val monthlyShare: Long,
    /** Each step, e.g. "연봉 36,000,000원 ÷ 12 = 기본급 3,000,000원". */
    val lines: List<String>,
    val warnings: List<String>,
)
