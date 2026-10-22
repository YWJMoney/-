package com.bamnat.core.pay

import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * [SHFT-04] What came in for a month's pay against what the calendar worked out. [expected] is the
 * take-home estimate when there is one (else the pay before tax), so tax alone does not read as a gap.
 */
data class PayGap(val month: YearMonth, val expected: Long, val received: Long, val dates: List<LocalDate>) {
    /** Received less expected: below zero when less came in. */
    val diff: Long get() = received - expected
    /** Within [PayGaps.TOLERANCE] of the estimate. */
    val matches: Boolean get() = abs(diff) <= PayGaps.TOLERANCE

    /** "예상보다 52,300원 적게 들어왔어요". */
    val text: String
        get() = when {
            matches -> "예상과 거의 같게 들어왔어요"
            diff < 0 -> "예상보다 ${"%,d".format(-diff)}원 적게 들어왔어요"
            else -> "예상보다 ${"%,d".format(diff)}원 많이 들어왔어요"
        }
}

object PayGaps {
    /** Differences up to this many won are rounding, not news. */
    const val TOLERANCE = 1_000L
    /** Days around the pay day a 급여 line still counts for that month (weekends, holidays, early pay). */
    const val WINDOW_DAYS = 7L

    /**
     * The 급여 income lines that are [month]'s pay: within [WINDOW_DAYS] of [payDate] when the pay day is
     * known, else dated in the month itself. Null when none came in yet.
     */
    fun received(month: YearMonth, payDate: LocalDate?, entries: List<MoneyEntry>): Pair<Long, List<LocalDate>>? {
        val pay = entries.filter { it.kind == MoneyKind.INCOME && payAmount(it) > 0 }.filter { e ->
            if (payDate != null) !e.date.isBefore(payDate.minusDays(WINDOW_DAYS)) && !e.date.isAfter(payDate.plusDays(WINDOW_DAYS))
            else YearMonth.from(e.date) == month
        }
        if (pay.isEmpty()) return null
        return pay.sumOf(::payAmount) to pay.map { it.date }.distinct().sorted()
    }

    /** The gap for [month], or null until its pay came in or when nothing was worked out. */
    fun of(month: YearMonth, expected: Long?, payDate: LocalDate?, entries: List<MoneyEntry>): PayGap? {
        if (expected == null || expected <= 0) return null
        val (amount, dates) = received(month, payDate, entries) ?: return null
        return PayGap(month, expected, amount, dates)
    }

    /** What of a line is 급여: all of a 급여 line, or the 급여 share of a split one (its main part included). */
    private fun payAmount(e: MoneyEntry): Long = e.shareOf(MoneyCategories.SALARY)
}
