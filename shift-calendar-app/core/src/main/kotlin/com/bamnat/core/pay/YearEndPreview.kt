package com.bamnat.core.pay

import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

/**
 * [PAYX-42] 연말정산 미리보기: the year's 결정세액 worked out roughly from the 세전 of each month, against the 소득세
 * withheld by the 간이세액표, so the user sees early whether February brings money back or takes more. Only the
 * deductions every 근로자 has are in: 근로소득공제, 기본공제, 국민연금·건강·고용보험료 공제, 근로소득세액공제,
 * 자녀세액공제 (or the 표준세액공제 when it is more). 카드·의료비·교육비·기부금·연금저축 and the like are not
 * asked and are named in [LEFT_OUT]. Articles: 소득세법 제47조·제50조·제51조의3·제52조·제55조·제59조·제59조의2·
 * 제59조의4 (2025년 귀속 기준, 확인 필요).
 */
object YearEndPreview {
    data class Result(
        val year: Int,
        val salary: Long,
        val earnedDeduction: Long,
        val taxBase: Long,
        val calculated: Long,
        val credits: Long,
        /** 결정세액 (소득세), and 지방소득세 its 10%. */
        val determined: Long,
        val local: Long,
        /** 소득세 withheld (간이세액표), 지방소득세 not included. */
        val withheld: Long,
        /** Months of [year] counted; the rest of the year is taken as the months so far went. */
        val months: Int,
    ) {
        /** Positive: money back in February (소득세 + 지방소득세); negative: more to pay. */
        val refund: Long get() = ((withheld - determined) * 1.1).roundToLong()
    }

    const val LEFT_OUT = "신용카드 등 사용액, 의료비·교육비·보험료·기부금, 월세, 연금저축·IRP, 주택자금은 넣지 않았어요. 넣으면 대개 더 돌려받아요."

    /** 근로소득공제 (제47조), capped at 2,000만 원. */
    fun earnedDeduction(salary: Long): Long {
        val s = salary.toDouble()
        return when {
            s <= 5_000_000 -> s * 0.7
            s <= 15_000_000 -> 3_500_000 + (s - 5_000_000) * 0.4
            s <= 45_000_000 -> 7_500_000 + (s - 15_000_000) * 0.15
            s <= 100_000_000 -> 12_000_000 + (s - 45_000_000) * 0.05
            else -> minOf(14_750_000 + (s - 100_000_000) * 0.02, 20_000_000.0)
        }.roundToLong()
    }

    /** 기본세율 (제55조 제1항). */
    fun basicTax(base: Long): Long {
        val b = base.toDouble()
        return when {
            b <= 0 -> 0.0
            b <= 14_000_000 -> b * 0.06
            b <= 50_000_000 -> b * 0.15 - 1_260_000
            b <= 88_000_000 -> b * 0.24 - 5_760_000
            b <= 150_000_000 -> b * 0.35 - 15_440_000
            b <= 300_000_000 -> b * 0.38 - 19_940_000
            b <= 500_000_000 -> b * 0.40 - 25_940_000
            b <= 1_000_000_000 -> b * 0.42 - 35_940_000
            else -> b * 0.45 - 65_940_000
        }.roundToLong()
    }

    /** 근로소득세액공제 (제59조) with its cap by 총급여. */
    fun earnedCredit(tax: Long, salary: Long): Long {
        val credit = if (tax <= 1_300_000) tax * 0.55 else 715_000 + (tax - 1_300_000) * 0.30
        val s = salary.toDouble()
        val cap = when {
            s <= 33_000_000 -> 740_000.0
            s <= 70_000_000 -> maxOf(660_000.0, 740_000 - (s - 33_000_000) * 0.008)
            s <= 120_000_000 -> maxOf(500_000.0, 660_000 - (s - 70_000_000) * 0.5)
            else -> maxOf(200_000.0, 500_000 - (s - 120_000_000) * 0.5)
        }
        return minOf(credit, cap).roundToLong()
    }

    /** 자녀세액공제 (제59조의2, 8세 이상 자녀): 1명 25만, 2명 55만, 셋째부터 1명마다 40만 원 (2025년 귀속, 확인 필요). */
    fun childCredit(children: Int): Long = when {
        children <= 0 -> 0
        children == 1 -> 250_000
        else -> 550_000L + 400_000L * (children - 2)
    }

    const val STANDARD_CREDIT = 130_000L

    /**
     * The year from its 세전 [gross] (taxable part only: 비과세 left out), the 국민연금 and 건강·장기요양·고용보험 the
     * worker paid, and the 소득세 withheld, all for the [months] counted so far, run on to twelve.
     */
    fun estimate(year: Int, gross: Long, insurance: Long, pension: Long, withheld: Long, months: Int, dependents: Int, children: Int): Result {
        val k = 12.0 / months.coerceIn(1, 12)
        val salary = (gross * k).roundToLong()
        val earned = earnedDeduction(salary)
        val base = (salary - earned - 1_500_000L * dependents.coerceAtLeast(1) - (pension * k).roundToLong()).coerceAtLeast(0)
        // 보험료 특별소득공제 or the 표준세액공제: whichever leaves less tax.
        val withInsurance = (base - (insurance * k).roundToLong()).coerceAtLeast(0)
        fun determined(taxBase: Long, standard: Boolean): Triple<Long, Long, Long> {
            val calc = basicTax(taxBase)
            val credits = earnedCredit(calc, salary) + childCredit(children) + if (standard) STANDARD_CREDIT else 0
            return Triple(calc, minOf(credits, calc), (calc - credits).coerceAtLeast(0))
        }
        val a = determined(withInsurance, standard = false)
        val b = determined(base, standard = true)
        val (taxBase, best) = if (a.third <= b.third) withInsurance to a else base to b
        return Result(year, salary, earned, taxBase, best.first, best.second, best.third / 10 * 10, best.third / 100 * 10,
            (withheld * k).roundToLong(), months.coerceIn(1, 12))
    }

    /**
     * The year so far worked out month by month with [calculator]: each month's 세전 and the 간이세액표 withholding
     * on it ([TakeHome.estimate]), from January to [upTo]. Null when no month has pay.
     */
    fun ofYear(profile: PayProfile, upTo: YearMonth, resolve: (LocalDate) -> ResolvedDay, calculator: PayCalculator = PayCalculator()): Result? {
        var gross = 0L; var ins = 0L; var pension = 0L; var tax = 0L; var n = 0
        for (m in 1..upTo.monthValue) {
            val ym = YearMonth.of(upTo.year, m)
            val pay = runCatching { calculator.calculate(profile, ym, resolve, checks = false) }.getOrNull() ?: continue
            if (pay.total <= 0) continue
            val free = TakeHome.nonTaxable(profile, ym, pay)
            val r = TakeHome.estimate(pay.total, TaxMode.INSURANCE_4, profile.taxFamily, ym.atDay(1), profile.taxChildren, free).deductions.toMap()
            gross += pay.total - free
            pension += r["국민연금"] ?: 0
            ins += (r["건강보험"] ?: 0) + (r["장기요양"] ?: 0) + (r["고용보험"] ?: 0)
            tax += r[TakeHome.TAX_LABEL] ?: 0
            n++
        }
        if (n == 0) return null
        return estimate(upTo.year, gross, ins, pension, tax, n, profile.taxFamily, profile.taxChildren)
    }
}
