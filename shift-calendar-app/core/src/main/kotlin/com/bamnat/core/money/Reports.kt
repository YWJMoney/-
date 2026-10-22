package com.bamnat.core.money

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** [LEDG-10] One category's spending in a report, its share of the whole and the period before. */
data class CategoryShare(val category: String, val amount: Long, val count: Int, val share: Float, val previous: Long) {
    val diff: Long get() = amount - previous
}

/**
 * [LEDG-10] A month looked at closely: where the money went and how that compares with the
 * month before, how fast it went day by day, which accounts and tags it went through, the
 * biggest payments, and the days nothing was spent.
 */
data class MonthReport(
    val month: YearMonth,
    val income: Long,
    val spent: Long,
    val previousIncome: Long,
    val previousSpent: Long,
    val categories: List<CategoryShare>,
    /** Spending added up day by day, to today for the month in progress. */
    val cumulative: List<Long>,
    /** The month before's, every day of it. */
    val previousCumulative: List<Long>,
    /** Spending by account, most first; a null id for lines with none. */
    val byAccount: List<Pair<Long?, Long>>,
    val byTag: List<Pair<String, Long>>,
    val biggest: List<MoneyEntry>,
    /** Spending on each day of the week, 월 to 일. */
    val byWeekday: List<Long>,
    /** Days gone by in the month with no spending at all (무지출). */
    val noSpendDays: Int,
    val daysCounted: Int,
) {
    val balance: Long get() = income - spent
    val spentDiff: Long get() = spent - previousSpent
    /** What the month before had spent by the same day, to set this month's pace against. */
    val previousSameDay: Long get() = previousCumulative.getOrElse(cumulative.size - 1) { previousCumulative.lastOrNull() ?: 0 }
    val hasData: Boolean get() = spent != 0L || income != 0L
}

/** [LEDG-10] A year month by month, with its totals and where the year's money went. */
data class YearReport(val year: Int, val months: List<MonthMoney>, val categories: List<CategoryShare>) {
    val income: Long get() = months.sumOf { it.income }
    val spent: Long get() = months.sumOf { it.spent }
    val balance: Long get() = income - spent
    /** Months with anything in them, so a year begun in October is not averaged over twelve. */
    val activeMonths: Int get() = months.count { it.income != 0L || it.spent != 0L }
    val averageSpent: Long get() = if (activeMonths == 0) 0 else spent / activeMonths
    val averageIncome: Long get() = if (activeMonths == 0) 0 else income / activeMonths
    /** Of the money that came in, how much is left, as a percentage; null with no income. */
    val savingRate: Int? get() = if (income <= 0) null else (balance * 100 / income).toInt()
}

object Reports {
    /** Job materials and costs, shown as a slice of their own so the slices add up to 나간 돈. */
    const val JOB_COSTS = "일감 경비"

    private fun shares(m: MonthMoney?): Map<String, Pair<Long, Int>> {
        if (m == null) return emptyMap()
        val out = m.expenseByCategory.associate { it.category to (it.amount to it.count) }.toMutableMap()
        if (m.jobCosts > 0) out[JOB_COSTS] = m.jobCosts to 0
        return out
    }

    private fun categories(now: Map<String, Pair<Long, Int>>, before: Map<String, Pair<Long, Int>>): List<CategoryShare> {
        val whole = now.values.sumOf { it.first }.coerceAtLeast(1)
        return now.filter { it.value.first != 0L }.map { (c, v) ->
            CategoryShare(c, v.first, v.second, v.first.toFloat() / whole, before[c]?.first ?: 0)
        }.sortedWith(compareByDescending<CategoryShare> { it.amount }.thenBy { it.category })
    }

    /** Spending on each day of [month], ledger and job costs together. */
    private fun daily(month: YearMonth, entries: List<MoneyEntry>, jobs: List<Job>): LongArray {
        val out = LongArray(month.lengthOfMonth())
        for (e in entries) if (e.kind == MoneyKind.EXPENSE && YearMonth.from(e.date) == month) out[e.date.dayOfMonth - 1] += e.amount
        for (j in jobs) if (j.costs > 0 && YearMonth.from(j.date) == month) out[j.date.dayOfMonth - 1] += j.costs
        return out
    }

    private fun LongArray.running(days: Int): List<Long> {
        var sum = 0L
        return (0 until days.coerceIn(0, size)).map { sum += this[it]; sum }
    }

    /**
     * [LEDG-10] The report for [current], with [previous] (the month before) to compare. The
     * month in progress is counted to [today]; one still ahead counts nothing yet.
     */
    fun month(current: MonthMoney, previous: MonthMoney?, book: MoneyBook, today: LocalDate): MonthReport {
        val month = current.month
        val now = YearMonth.from(today)
        val days = when { month == now -> today.dayOfMonth; month.isBefore(now) -> month.lengthOfMonth(); else -> 0 }
        val day = daily(month, book.entries, book.jobs)
        val before = daily(month.minusMonths(1), book.entries, book.jobs)
        val spending = book.entries.filter { it.kind == MoneyKind.EXPENSE && YearMonth.from(it.date) == month }
        val byAccount = spending.groupBy { MoneyAccounts.of(it, book.accounts) }.map { (a, list) -> a to list.sumOf { it.amount } }
            .filter { it.second != 0L }.sortedByDescending { it.second }
        val byTag = spending.flatMap { e -> e.tags.map { it to e.amount } }.groupBy { it.first }.map { (t, list) -> t to list.sumOf { it.second } }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
        val weekday = LongArray(7)
        for (e in spending) weekday[e.date.dayOfWeek.value - 1] += e.amount
        return MonthReport(
            month = month, income = current.income, spent = current.spent,
            previousIncome = previous?.income ?: 0, previousSpent = previous?.spent ?: 0,
            categories = categories(shares(current), shares(previous)),
            cumulative = day.running(days), previousCumulative = before.running(before.size),
            byAccount = byAccount, byTag = byTag,
            biggest = spending.filter { it.amount > 0 }.sortedWith(compareByDescending<MoneyEntry> { it.amount }.thenBy { it.date }).take(5),
            byWeekday = weekday.toList(),
            noSpendDays = (0 until days).count { day[it] <= 0 }, daysCounted = days,
        )
    }

    /** [LEDG-10] A year from its twelve months (January first). */
    fun year(year: Int, months: List<MonthMoney>, previousYear: List<MonthMoney> = emptyList()): YearReport {
        fun total(list: List<MonthMoney>): Map<String, Pair<Long, Int>> {
            val out = HashMap<String, Pair<Long, Int>>()
            for (m in list) for ((c, v) in shares(m)) out.merge(c, v) { a, b -> (a.first + b.first) to (a.second + b.second) }
            return out
        }
        return YearReport(year, months.sortedBy { it.month }, categories(total(months), total(previousYear)))
    }

    /** "월" .. "일" for [Reports.month]'s weekdays. */
    val WEEKDAYS: List<String> = DayOfWeek.entries.map { FixedCost.WEEKDAYS[it.value - 1] }
}
