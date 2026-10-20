package com.bamnat.core.money

/** [COMN-02][COMN-03] One calendar's month in 모든 달력 합계, with its work days and hours. */
data class CalendarMonth(
    val calendar: String,
    val name: String,
    val money: MonthMoney,
    val workDays: Int = 0,
    val workMinutes: Int = 0,
)

/**
 * [COMN-02][COMN-03] Every calendar's month added up: what came in, what went out and what is left,
 * whatever kind of work each calendar is. Each calendar keeps its own book, so nothing is counted twice.
 */
data class AllCalendarsMonth(val rows: List<CalendarMonth>) {
    val income: Long get() = rows.sumOf { it.money.income }
    val spent: Long get() = rows.sumOf { it.money.spent }
    val balance: Long get() = income - spent
    val workDays: Int get() = rows.sumOf { it.workDays }
    val workMinutes: Int get() = rows.sumOf { it.workMinutes }
}
