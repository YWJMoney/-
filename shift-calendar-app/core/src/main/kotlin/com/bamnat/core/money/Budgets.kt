package com.bamnat.core.money

import java.time.LocalDate
import java.time.YearMonth

/** [LEDG-06] A monthly budget: for the whole month ([TOTAL]) or one spending category. Repeats every month. */
data class MoneyBudget(val category: String, val amount: Long) {
    val isTotal: Boolean get() = category == TOTAL

    companion object {
        const val TOTAL = ""
    }
}

enum class BudgetLevel { OK, NEAR, OVER }

/** [LEDG-06] One budget against what was spent, and where the month's pace is heading. */
data class BudgetLine(val category: String, val budget: Long, val spent: Long, val forecast: Long) {
    val left: Long get() = budget - spent
    val fraction: Float get() = if (budget <= 0) 0f else spent.toFloat() / budget
    val level: BudgetLevel get() = when {
        spent > budget -> BudgetLevel.OVER
        fraction >= Budgets.NEAR_AT -> BudgetLevel.NEAR
        else -> BudgetLevel.OK
    }
    /** On course to go over by the month's end, though not over yet. */
    val headedOver: Boolean get() = level != BudgetLevel.OVER && forecast > budget
    val isTotal: Boolean get() = category == MoneyBudget.TOTAL
}

/** [LEDG-06] A month's budgets: the whole, each category with one, and what is left per day. */
data class BudgetStatus(
    val month: YearMonth,
    /** The whole month; the sum of the category budgets when no whole-month budget is set; null with no budget. */
    val total: BudgetLine?,
    /** Categories with a budget, the ones over or nearest to it first. */
    val categories: List<BudgetLine>,
    /** Days left including today for this month, every day for a later month, 0 for one gone by. */
    val daysLeft: Int,
    /** What may still be spent each day from today on, after the fixed costs still to leave; null without a budget or for a past month. */
    val perDay: Long?,
    val todaySpent: Long,
    /** Fixed costs still to leave this month, kept aside in [perDay]. */
    val fixedLeft: Long,
    /** The share of the month gone by, 0..1, to set the money against the days. */
    val elapsed: Float,
) {
    val hasBudget: Boolean get() = total != null
    val over: List<BudgetLine> get() = categories.filter { it.level == BudgetLevel.OVER }
    /** What is left for today itself; below zero once today went past its share. */
    val todayLeft: Long? get() = perDay?.let { it - todaySpent }
}

object Budgets {
    /** A budget 80% used is close to its end. */
    const val NEAR_AT = 0.8f

    /**
     * [LEDG-06] Budgets against the ledger's spending in [month] (job costs and transfers are not
     * spending here). A split payment counts under each of its categories [LEDG-11]. The forecast
     * adds the pace of everyday spending to what is spent, without repeating lines, which come
     * once ([fixedLeft] is added to the whole month's).
     */
    fun status(budgets: List<MoneyBudget>, entries: List<MoneyEntry>, month: YearMonth, today: LocalDate, fixedLeft: Long = 0): BudgetStatus {
        val spent = entries.filter { it.kind == MoneyKind.EXPENSE && YearMonth.from(it.date) == month }
        val byCategory = HashMap<String, Long>()
        val everyday = HashMap<String, Long>()
        for (e in spent) for ((c, a) in e.shares()) {
            byCategory.merge(c, a, Long::plus)
            if (e.source != MoneySource.FIXED) everyday.merge(c, a, Long::plus)
        }
        val length = month.lengthOfMonth()
        val now = YearMonth.from(today)
        val current = now == month
        val gone = when { current -> today.dayOfMonth; month.isBefore(now) -> length; else -> 0 }
        val daysLeft = when { current -> length - today.dayOfMonth + 1; month.isAfter(now) -> length; else -> 0 }
        val after = if (current) length - today.dayOfMonth else 0
        fun forecast(spentSoFar: Long, everydaySoFar: Long): Long =
            if (!current || gone == 0) spentSoFar else spentSoFar + (everydaySoFar.coerceAtLeast(0) * after / gone)
        val fixedAside = if (current) fixedLeft else 0
        val all = spent.sumOf { it.amount }
        val explicit = budgets.firstOrNull { it.isTotal && it.amount > 0 }?.amount
        val categoryBudgets = budgets.filter { !it.isTotal && it.amount > 0 }
        val totalBudget = explicit ?: categoryBudgets.sumOf { it.amount }.takeIf { it > 0 }
        val total = totalBudget?.let { BudgetLine(MoneyBudget.TOTAL, it, all, forecast(all, everyday.values.sum()) + fixedAside) }
        val lines = categoryBudgets.map { b ->
            BudgetLine(b.category, b.amount, byCategory[b.category] ?: 0, forecast(byCategory[b.category] ?: 0, everyday[b.category] ?: 0))
        }.sortedWith(compareBy<BudgetLine> { it.level != BudgetLevel.OVER }.thenByDescending { it.fraction }.thenBy { it.category })
        val todaySpent = if (current) spent.filter { it.date == today }.sumOf { it.amount } else 0
        val perDay = if (total == null || daysLeft == 0) null else
            ((total.budget - (all - todaySpent) - fixedAside) / daysLeft).coerceAtLeast(0)
        return BudgetStatus(month, total, lines, daysLeft, perDay, todaySpent, fixedAside, gone.toFloat() / length)
    }

    /**
     * [LEDG-06] The budgets a new line pushes over: categories (and the whole month) that were
     * within their budget before [entry] and are over it with it, so the app can say so once.
     */
    fun crossedBy(entry: MoneyEntry, budgets: List<MoneyBudget>, entries: List<MoneyEntry>, today: LocalDate): List<BudgetLine> {
        if (entry.kind != MoneyKind.EXPENSE || entry.amount <= 0) return emptyList()
        val month = YearMonth.from(entry.date)
        // An edited line replaces the one it was; a new one is added.
        val before = status(budgets, entries, month, today)
        val after = status(budgets, entries.filter { entry.id == 0L || it.id != entry.id } + entry, month, today)
        val was = (listOfNotNull(before.total) + before.categories).associateBy { it.category }
        return (listOfNotNull(after.total) + after.categories).filter { it.level == BudgetLevel.OVER && was[it.category]?.level != BudgetLevel.OVER }
    }
}
