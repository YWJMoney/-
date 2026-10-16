package com.bamnat.core.pay

import java.time.YearMonth

/**
 * A closed month: the result and the rate version it was made with, so later changes to rules
 * or rates never silently rewrite a past month [PAY-10]. Recalculating keeps the old snapshot.
 */
data class CalcSnapshot(
    val month: YearMonth,
    val total: Long,
    val lines: List<PayLine>,
    val ratesVersion: String,
    val closedAtMillis: Long,
    /** Earlier snapshots of the same month, newest first, kept when the user recalculated. */
    val previous: List<CalcSnapshot> = emptyList(),
) {
    fun recalculated(result: MonthPay, atMillis: Long): CalcSnapshot =
        CalcSnapshot(month, result.total, result.lines, result.ratesVersion, atMillis, listOf(copy(previous = emptyList())) + previous)

    companion object {
        fun close(result: MonthPay, atMillis: Long) =
            CalcSnapshot(result.month, result.total, result.lines, result.ratesVersion, atMillis)
    }
}
