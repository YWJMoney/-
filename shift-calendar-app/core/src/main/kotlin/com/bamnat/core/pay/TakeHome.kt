package com.bamnat.core.pay

import com.bamnat.core.money.Withholding
import java.time.LocalDate

/** [HRLY-04] How tax comes off the pay: nothing, 3.3% on 사업소득, or 4대보험 with 근로소득세. */
enum class TaxMode { NONE, WITHHOLDING_3_3, INSURANCE_4 }

/** [HRLY-04] Pay before tax, what comes off it line by line, and what reaches the account. */
data class TakeHomeResult(val gross: Long, val deductions: List<Pair<String, Long>>, val net: Long)

/**
 * [HRLY-04] 예상 실수령 from a month's pay before tax. 3.3% is [Withholding.of]. 4대보험 takes the employee half of
 * the month's year in [RateTable] (국민연금 of [pensionBase] within that July's 기준소득월액 limits, 건강보험, 장기요양,
 * 고용보험 실업급여), each cut to 10원. [PAYR-04] 근로소득세 is the official 근로소득 간이세액표 ([WithholdingTable],
 * 소득세법 시행령 별표 2) for the pay less 비과세 (식대 월 20만 원까지 등, 소득세법 제12조 제3호), the 공제대상가족 수
 * and 8~20세 자녀 수; 지방소득세 is a tenth (지방세법 제103조의13). The employer may pick 80%·120% of the table
 * (시행령 제194조 제1항) and 연말정산 settles the year, so it stays labelled as the table's amount.
 */
object TakeHome {
    const val PENSION_RATE = 0.0475
    const val HEALTH_RATE = 0.03595
    const val CARE_OF_HEALTH = 0.1314
    const val EMPLOYMENT_RATE = 0.009
    /** 국민연금 기준소득월액 하한·상한 (2025.7~2026.6 고시): pension is on the pay kept within these. */
    const val PENSION_BASE_MIN = 400_000L
    const val PENSION_BASE_MAX = 6_370_000L

    /** [PAYR-01] The employee's shares on [date]: half of each 합계 rate in [RateTable]; 장기요양 kept from the last known year until fixed. */
    data class Shares(
        val pension: Double, val health: Double, val careOfHealth: Double, val baseMin: Long, val baseMax: Long,
        val employment: Double = EMPLOYMENT_RATE,
    )

    /** [REMOTE-02] From [RateTable.current], so a signed rates file (e.g. the 2027 장기요양 rate) changes it. */
    fun sharesOn(date: LocalDate): Shares {
        val r = RateTable.on(date)
        val known = RateTable.current.filter { !it.effectiveFrom.isAfter(date) && it.longTermCareRate != null && it.healthRate != null }
            .maxByOrNull { it.effectiveFrom }
        val care = known?.let { it.longTermCareRate!! / it.healthRate!! } ?: CARE_OF_HEALTH
        val base = RateTable.pensionBases.filter { !it.from.isAfter(date) }.maxByOrNull { it.from }
        return Shares(
            (r.pensionRate ?: PENSION_RATE * 2) / 2, (r.healthRate ?: HEALTH_RATE * 2) / 2, care,
            base?.min ?: PENSION_BASE_MIN, base?.max ?: PENSION_BASE_MAX, r.unemploymentRate / 2,
        )
    }

    private val DEFAULT_SHARES = Shares(PENSION_RATE, HEALTH_RATE, CARE_OF_HEALTH, PENSION_BASE_MIN, PENSION_BASE_MAX)

    /**
     * [PAYR-01] With [on], the year's rates and 기준소득월액 limits for that day; without it, the 2026 first-half ones.
     * [PAYR-04] [dependents] is the 간이세액표's 공제대상가족 수 (본인 포함), [children] the 8~20세 자녀 among them, and
     * [nonTaxable] the part of [gross] that is not taxed (식대 등), which the table leaves out. The 4대보험 bases leave
     * it out too: 보수·기준소득월액 are pay less 소득세법 비과세 근로소득 (국민건강보험법 시행령 제33조, 국민연금법
     * 제3조 제1항 제3호, 고용산재보험료징수법 제2조 제3호).
     */
    fun estimate(
        gross: Long, mode: TaxMode, dependents: Int = 1, on: LocalDate? = null, children: Int = 0, nonTaxable: Long = 0,
    ): TakeHomeResult {
        val sh = on?.let(::sharesOn) ?: DEFAULT_SHARES
        val g = gross.coerceAtLeast(0)
        val free = nonTaxable.coerceIn(0, g)
        val deductions: List<Pair<String, Long>> = when (mode) {
            TaxMode.NONE -> emptyList()
            TaxMode.WITHHOLDING_3_3 -> listOf("원천징수 3.3%" to Withholding.of(g))
            TaxMode.INSURANCE_4 -> {
                val taxable = g - free
                val pension = if (taxable == 0L) 0L else cut10((taxable / 1_000 * 1_000).coerceIn(sh.baseMin, sh.baseMax) * sh.pension)
                val health = cut10(taxable * sh.health)
                val care = cut10(health * sh.careOfHealth)
                val employment = cut10(taxable * sh.employment)
                val table = WithholdingTable.on(on ?: TABLE_DAY) ?: WithholdingTable.on(TABLE_DAY)
                val tax = table?.tax(taxable, dependents, children) ?: incomeTax(taxable, dependents, pension)
                listOf(
                    "국민연금" to pension,
                    "건강보험" to health,
                    "장기요양" to care,
                    "고용보험" to employment,
                    TAX_LABEL to tax,
                    LOCAL_TAX_LABEL to cut10(tax * 0.1),
                ).filter { it.second > 0 }
            }
        }
        return TakeHomeResult(g, deductions, g - deductions.sumOf { it.second })
    }

    /**
     * [PAYX-23] The pay before tax whose [estimate] leaves [net] (실수령액), found by halving the range: for a payslip
     * where only the amount paid into the account was typed. The deductions only grow with the pay, so the result
     * is the smallest pay that leaves at least [net]; within 10원 of it, as each deduction is cut to 10원.
     * [TaxMode.NONE] takes nothing off, so it is [net] itself.
     */
    fun grossFromNet(
        net: Long, mode: TaxMode, dependents: Int = 1, on: LocalDate? = null, children: Int = 0, nonTaxable: Long = 0,
    ): Long {
        if (net <= 0 || mode == TaxMode.NONE) return net.coerceAtLeast(0)
        var lo = net
        var hi = net * 2 + 1_000_000
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (estimate(mid, mode, dependents, on, children, nonTaxable).net >= net) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** 소득세법 시행령 제17조의2: 식사대 월 20만 원 이하 (식사를 따로 받지 않을 때). Each 비과세 allowance counts up to this. */
    const val NON_TAXABLE_LIMIT = 200_000L

    /**
     * [PAYR-07] The month's 비과세: monthly allowances marked not [PayItem.taxable] (식대 등), each up to
     * [NON_TAXABLE_LIMIT]. Bonuses and per-day pay are always taxed here.
     */
    fun nonTaxable(profile: PayProfile, month: java.time.YearMonth): Long =
        profile.items.filter { !it.taxable && it.cadence == Cadence.MONTHLY && !it.isFixedOvertime }
            .sumOf { it.amount.on(month).value.coerceIn(0, NON_TAXABLE_LIMIT) }

    /**
     * [PAYX-25] With [pay], also the 생산직 연장·야간·휴일 수당 비과세 ([productionFree]); [usedThisYear] is what earlier
     * months of the year already took of its 연 240만원.
     */
    fun nonTaxable(profile: PayProfile, month: java.time.YearMonth, pay: MonthPay?, usedThisYear: Long = 0): Long =
        nonTaxable(profile, month) + (pay?.let { productionFree(profile, it, usedThisYear).amount } ?: 0)

    /** [PAYX-25] 소득세법 시행령 제17조 (대통령령 제36129호, 2026 귀속부터): 월정액급여 260만원 이하. */
    const val PRODUCTION_MONTHLY_LIMIT = 2_600_000L
    /** 직전 과세기간 총급여액 3,700만원 이하. */
    const val PRODUCTION_PREVIOUS_YEAR_LIMIT = 37_000_000L
    /** 연 240만원 (광산·일용근로자는 한도 없음, 이 앱은 그 경우를 따로 다루지 않아요). */
    const val PRODUCTION_YEAR_LIMIT = 2_400_000L

    /** [PAYX-25] The month's 생산직 비과세 and why it is what it is. */
    data class ProductionFree(val amount: Long, val premiums: Long, val monthly: Long, val note: String)

    /**
     * [PAYX-25] 생산직 등 근로자의 연장·야간·휴일근로 급여 비과세 (소득세법 제12조 제3호 더목, 시행령 제17조, research/1.23-K-원문.md
     * 1장): for a [PayProfile.productionWorker] whose 월정액급여 (정기 급여 less bonuses, 비과세 and the extra-work pay) is
     * at most [PRODUCTION_MONTHLY_LIMIT] and whose last year's 총급여 is at most [PRODUCTION_PREVIOUS_YEAR_LIMIT] (not entered:
     * taken as within), the month's 연장·야간·휴일 pay is not taxed, up to what is left of [PRODUCTION_YEAR_LIMIT].
     */
    fun productionFree(profile: PayProfile, pay: MonthPay, usedThisYear: Long = 0): ProductionFree {
        val extraKinds = setOf(LineKind.OVERTIME, LineKind.NIGHT, LineKind.HOLIDAY)
        val premiums = pay.lines.filter { it.kind in extraKinds }.sumOf { it.amount }.coerceAtLeast(0)
        val bonusNames = profile.items.filter { it.cadence == Cadence.BONUS }.map { it.name }.toSet()
        val monthly = pay.lines.filter { it.kind !in extraKinds && it.kind != LineKind.EXTRA && it.kind != LineKind.EXTRA_HOURS && it.label !in bonusNames }
            .sumOf { it.amount } - nonTaxable(profile, pay.month)
        if (!profile.productionWorker) return ProductionFree(0, premiums, monthly, "")
        val previous = profile.previousYearPay
        return when {
            premiums == 0L -> ProductionFree(0, 0, monthly, "이번 달 연장·야간·휴일 수당이 없어요")
            monthly > PRODUCTION_MONTHLY_LIMIT -> ProductionFree(0, premiums, monthly,
                "월정액급여 ${won(monthly)}이 ${won(PRODUCTION_MONTHLY_LIMIT)}을 넘어 연장·야간 수당에 세금이 붙어요")
            previous != null && previous > PRODUCTION_PREVIOUS_YEAR_LIMIT -> ProductionFree(0, premiums, monthly,
                "작년 총급여 ${won(previous)}이 ${won(PRODUCTION_PREVIOUS_YEAR_LIMIT)}을 넘어 연장·야간 수당에 세금이 붙어요")
            else -> {
                val left = (PRODUCTION_YEAR_LIMIT - usedThisYear).coerceAtLeast(0)
                val free = minOf(premiums, left)
                ProductionFree(free, premiums, monthly, "생산직 연장·야간·휴일 수당 ${won(free)} 비과세 (연 ${won(PRODUCTION_YEAR_LIMIT)}까지" +
                    (if (usedThisYear > 0) ", 올해 ${won(usedThisYear)} 씀" else "") + (if (previous == null) ", 작년 총급여는 3,700만 원 이하로 가정" else "") +
                    ", 소득세법 시행령 제17조)")
            }
        }
    }

    /**
     * Names of the usual 비과세 allowances (소득세법 제12조 제3호 · 시행령 제12조·제17조의2: 식사대, 자가운전보조금,
     * 6세 이하 보육수당). A new allowance named so starts as 비과세, and only these show the switch.
     */
    fun looksNonTaxable(name: String): Boolean =
        listOf("식대", "식비", "중식", "석식", "자가운전", "차량유지", "보육", "육아").any { it in name.replace(" ", "") }

    /**
     * [PAYX-23] 국민연금 is estimated on this month's pay, but the 공단 sets 기준소득월액 once a year from last year's
     * income for July to June (the year [RateTable.pensionBases] runs on), so the bill can differ; said wherever the 4대보험 lines show.
     */
    const val PENSION_NOTE = "국민연금은 이번 달 급여로 어림했어요. 실제 고지액은 작년 소득으로 정한 금액(7월부터 1년)이라 다를 수 있어요."

    const val TAX_LABEL = "근로소득세 (간이세액표)"
    const val LOCAL_TAX_LABEL = "지방소득세"
    /** [PAYR-04] The first day of the built-in table (2026-02-27 개정, 2026-03-01 이후 원천징수분); earlier months use it too. */
    private val TABLE_DAY: LocalDate = LocalDate.of(2026, 3, 1)

    /** 기준소득월액: the pay, at least [PENSION_BASE_MIN] and at most [PENSION_BASE_MAX] (cut to 1,000원 as the 공단 does). */
    fun pensionBase(gross: Long): Long = (gross / 1_000 * 1_000).coerceIn(PENSION_BASE_MIN, PENSION_BASE_MAX)

    /**
     * [PAYR-04] Only when the table file is missing (it ships in the app): a month's 근로소득세, simplified from the 간이세액표: a year of [monthly] pay less 근로소득공제, 인적공제
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
