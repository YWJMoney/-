package com.bamnat.core.pay

import com.bamnat.core.money.Withholding

/** [HRLY-04] How tax comes off the pay: nothing, 3.3% on 사업소득, or 4대보험 with 근로소득세. */
enum class TaxMode { NONE, WITHHOLDING_3_3, INSURANCE_4 }

/** [HRLY-04] Pay before tax, what comes off it line by line, and what reaches the account. */
data class TakeHomeResult(val gross: Long, val deductions: List<Pair<String, Long>>, val net: Long)

/**
 * [HRLY-04] 예상 실수령 from a month's pay before tax. 3.3% is [Withholding.of]. 4대보험 takes the 2026
 * employee shares (국민연금 4.75% of [pensionBase], 건강보험 3.595%, 장기요양 = 건강보험료 × 13.14%, 고용보험 0.9%), each
 * cut to 10원, and a 근로소득세 worked out like the 간이세액표 from a year of this month's pay. The tax is
 * an approximation, labelled 참고용: the real one comes from the employer's table and year-end settlement.
 */
object TakeHome {
    const val PENSION_RATE = 0.0475
    const val HEALTH_RATE = 0.03595
    const val CARE_OF_HEALTH = 0.1314
    const val EMPLOYMENT_RATE = 0.009
    /** 국민연금 기준소득월액 하한·상한 (2025.7~2026.6 고시): pension is on the pay kept within these. */
    const val PENSION_BASE_MIN = 400_000L
    const val PENSION_BASE_MAX = 6_370_000L

    fun estimate(gross: Long, mode: TaxMode, dependents: Int = 1): TakeHomeResult {
        val g = gross.coerceAtLeast(0)
        val deductions: List<Pair<String, Long>> = when (mode) {
            TaxMode.NONE -> emptyList()
            TaxMode.WITHHOLDING_3_3 -> listOf("원천징수 3.3%" to Withholding.of(g))
            TaxMode.INSURANCE_4 -> {
                val pension = if (g == 0L) 0L else cut10(pensionBase(g) * PENSION_RATE)
                val health = cut10(g * HEALTH_RATE)
                val care = cut10(health * CARE_OF_HEALTH)
                val employment = cut10(g * EMPLOYMENT_RATE)
                val tax = incomeTax(g, dependents, pension)
                listOf(
                    "국민연금" to pension,
                    "건강보험" to health,
                    "장기요양" to care,
                    "고용보험" to employment,
                    "근로소득세 (참고용)" to tax,
                    "지방소득세 (참고용)" to cut10(tax * 0.1),
                ).filter { it.second > 0 }
            }
        }
        return TakeHomeResult(g, deductions, g - deductions.sumOf { it.second })
    }

    /** 기준소득월액: the pay, at least [PENSION_BASE_MIN] and at most [PENSION_BASE_MAX] (cut to 1,000원 as the 공단 does). */
    fun pensionBase(gross: Long): Long = (gross / 1_000 * 1_000).coerceIn(PENSION_BASE_MIN, PENSION_BASE_MAX)

    /**
     * A month's 근로소득세, simplified from the 간이세액표: a year of [monthly] pay less 근로소득공제, 인적공제
     * (150만 원 a head), the year's pension and the table's 특별소득공제 (310만 원 + a share of the pay), taxed
     * by the 2026 brackets less 근로소득세액공제, then a twelfth of it.
     */
    internal fun incomeTax(monthly: Long, dependents: Int, monthlyPension: Long): Long {
        val salary = monthly * 12.0
        val earnedDeduction = when {
            salary <= 5_000_000 -> salary * 0.7
            salary <= 15_000_000 -> 3_500_000 + (salary - 5_000_000) * 0.4
            salary <= 45_000_000 -> 7_500_000 + (salary - 15_000_000) * 0.15
            salary <= 100_000_000 -> 12_000_000 + (salary - 45_000_000) * 0.05
            else -> minOf(14_750_000 + (salary - 100_000_000) * 0.02, 20_000_000.0)
        }
        val special = when {
            salary <= 30_000_000 -> 3_100_000 + salary * 0.04
            salary <= 45_000_000 -> 3_100_000 + salary * 0.04 - (salary - 30_000_000) * 0.05
            salary <= 70_000_000 -> 3_100_000 + salary * 0.015
            salary <= 120_000_000 -> 3_100_000 + salary * 0.005
            else -> 0.0
        }
        val base = salary - earnedDeduction - 1_500_000.0 * dependents.coerceAtLeast(1) - monthlyPension * 12.0 - special
        if (base <= 0) return 0
        val tax = when {
            base <= 14_000_000 -> base * 0.06
            base <= 50_000_000 -> base * 0.15 - 1_260_000
            base <= 88_000_000 -> base * 0.24 - 5_760_000
            base <= 150_000_000 -> base * 0.35 - 15_440_000
            base <= 300_000_000 -> base * 0.38 - 19_940_000
            base <= 500_000_000 -> base * 0.40 - 25_940_000
            base <= 1_000_000_000 -> base * 0.42 - 35_940_000
            else -> base * 0.45 - 65_940_000
        }
        val creditCap = when {
            salary <= 33_000_000 -> 740_000.0
            salary <= 70_000_000 -> maxOf(660_000.0, 740_000 - (salary - 33_000_000) * 0.008)
            else -> maxOf(500_000.0, 660_000 - (salary - 70_000_000) * 0.5)
        }
        val credit = minOf(if (tax <= 1_300_000) tax * 0.55 else 715_000 + (tax - 1_300_000) * 0.3, creditCap)
        return cut10(((tax - credit).coerceAtLeast(0.0)) / 12)
    }

    private fun cut10(v: Double): Long = (v.toLong() / 10) * 10
    private fun cut10(v: Long): Long = v / 10 * 10
}
