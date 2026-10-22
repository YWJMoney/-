package com.bamnat.core.work

import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import java.time.YearMonth

/**
 * [SHR-11] What money a schedule link can carry, by how the user works: an employee's pay, a
 * freelancer's job income, a shop's sales. The server and the receiving app know it as `payKind`.
 */
enum class MoneyShare(val key: String, val label: String) {
    PAY("pay", "급여"),
    JOBS("jobs", "일감 수입"),
    SALES("sales", "매출");

    companion object {
        fun of(style: WorkStyle?): MoneyShare = when {
            style?.usesSales == true -> SALES
            style?.usesJobs == true -> JOBS
            else -> PAY
        }

        fun ofKey(key: String?): MoneyShare = entries.firstOrNull { it.key == key } ?: PAY
    }
}

/** [SHR-11] One month's figure for a link, with the short word for where it came from. */
data class MonthFigure(val month: YearMonth, val amount: Long, val label: String)

/**
 * [SHR-11] Monthly figures for a link from what the user wrote down. Pay that the engine works out
 * comes from the app, which owns the calculator; here only recorded money is added up, so a month
 * with nothing written is left out rather than sent as 0.
 */
object MoneyShareFigures {
    /** A shop's 매출 per month: what came in, before costs, as the user asked to show. */
    fun sales(months: List<YearMonth>, sales: List<DailySales>): List<MonthFigure> = months.mapNotNull { m ->
        val days = sales.filter { YearMonth.from(it.date) == m && it.sales != 0L }
        if (days.isEmpty()) null else MonthFigure(m, days.sumOf { it.sales }.coerceAtLeast(0), "매출")
    }

    /** A freelancer's job income per month, on each job's first day, after 3.3% when it is held back. */
    fun jobs(months: List<YearMonth>, jobs: List<Job>): List<MonthFigure> = months.mapNotNull { m ->
        val inMonth = jobs.filter { YearMonth.from(it.date) == m && it.amount > 0 && !it.cancelled }
        if (inMonth.isEmpty()) null else MonthFigure(m, inMonth.sumOf { it.payable }.coerceAtLeast(0), "일감 ${inMonth.size}건")
    }

    /**
     * Salary the user wrote in the ledger (분류 급여, or a salary-like one such as 월급·상여·알바비), for someone
     * with no pay calculation set up. A split line counts only its salary parts.
     */
    fun recordedPay(months: List<YearMonth>, entries: List<MoneyEntry>): List<MonthFigure> = months.mapNotNull { m ->
        val paid = entries.filter { it.kind == MoneyKind.INCOME && YearMonth.from(it.date) == m }
            .flatMap { e -> e.shares().filter { isSalary(it.first) } }
        if (paid.isEmpty()) null else MonthFigure(m, paid.sumOf { it.second }.coerceAtLeast(0), "받은 급여")
    }

    // [SHR-11] 급여 (the built-in category, which 입금 알림 with 급여 also lands in) and the names people give
    // pay: 월급, 주급, 일당, 상여(금), 알바비, 임금, with a month before or a note after (10월 월급, 급여(회사), 급여 - 본사).
    private val SALARY_NAME = Regex("^(\\d{1,2}월\\s*)?(급여|월급|주급|일급|일당|상여금?|알바비|알바\\s*급여|임금)(\\s*[(\\[\\-·:/].*)?$")

    fun isSalary(category: String): Boolean = category.trim().let { it == MoneyCategories.SALARY || SALARY_NAME.matches(it) }
}
