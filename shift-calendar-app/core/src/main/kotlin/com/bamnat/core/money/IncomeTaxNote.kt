package com.bamnat.core.money

import java.time.LocalDate

/**
 * [LEDG-26] 종합소득세 참고 정리: one year of a field worker's or a shop's 사업 수입, the costs written
 * down, and the tax already held back, gathered for the 5월 신고. It only adds up what the user wrote;
 * it never works out the tax owed, files, or promises a refund (세무 조언이 아니에요).
 *
 * Sources (checked 2026-10-22; 원문 대조는 묶음 A 184):
 * - 소득세법 제129조 제1항 제3호: 원천징수대상 사업소득 100분의 3 (secondary: taxly.kr/post/542).
 * - 지방세법 제103조의13: 특별징수하는 지방소득세는 소득세의 100분의 10, so 3% + 0.3% = "3.3%" ([Withholding]).
 * - 소득세법 제70조 제1항: 종합소득 과세표준 확정신고는 다음 해 5월 1일부터 5월 31일까지; a deadline on a
 *   weekend or public holiday moves to the next working day (국세기본법 제5조), as [TaxDeadlines.workday].
 */
object IncomeTaxNote {
    data class Year(
        val year: Int,
        /** 사업 수입 before anything was held back: jobs' amounts and a shop's sales. */
        val income: Long,
        /** Costs the user wrote: spending lines, jobs' costs and a shop's daily costs. */
        val costs: Long,
        /** 소득세 3% held back on jobs. */
        val withheldIncomeTax: Long,
        /** 지방소득세, a tenth of that. */
        val withheldLocalTax: Long,
        val jobs: Int,
        val salesDays: Int,
        /** The filing window in the next year, its end moved off weekends and holidays. */
        val fileFrom: LocalDate,
        val fileTo: LocalDate,
    ) {
        val withheld: Long get() = withheldIncomeTax + withheldLocalTax
        /** 수입 − 적은 경비, for reference only: the tax office's own 필요경비 rules decide what counts. */
        val left: Long get() = income - costs
        val isEmpty: Boolean get() = income == 0L && costs == 0L
    }

    fun of(year: Int, jobs: List<Job>, sales: List<DailySales>, entries: List<MoneyEntry>): Year {
        val j = jobs.filter { it.date.year == year && !it.cancelled }
        val s = sales.filter { it.date.year == year }
        val spent = entries.filter { it.date.year == year && it.kind == MoneyKind.EXPENSE }.sumOf { it.amount }
        // Each job's 3.3% split back into its two taxes, as the payer held them back.
        val incomeTax = j.sumOf { if (it.withheld > 0) it.amount * 3 / 100 / 10 * 10 else 0L }
        val total = j.sumOf { it.withheld }
        return Year(
            year = year,
            income = j.sumOf { it.amount } + s.sumOf { it.sales },
            costs = spent + j.sumOf { it.costs } + s.sumOf { it.costs },
            withheldIncomeTax = incomeTax.coerceAtMost(total),
            withheldLocalTax = (total - incomeTax).coerceAtLeast(0),
            jobs = j.size,
            salesDays = s.count { it.sales != 0L },
            fileFrom = LocalDate.of(year + 1, 5, 1),
            fileTo = TaxDeadlines.workday(LocalDate.of(year + 1, 5, 31)),
        )
    }

    /** Said under the numbers, every time. */
    const val NOT_ADVICE = "참고용 합계예요. 세무 조언이 아니고, 낼 세금이나 돌려받을 돈을 계산하지 않아요. " +
        "경비로 인정되는지와 신고 방법은 홈택스나 국세상담센터(126)에서 확인해요."
}
