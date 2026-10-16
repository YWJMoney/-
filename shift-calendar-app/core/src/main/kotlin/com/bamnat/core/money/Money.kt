package com.bamnat.core.money

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** [LEDG-01] Money moving out, in, or between the user's own accounts (not counted). */
enum class MoneyKind(val label: String) { EXPENSE("지출"), INCOME("수입"), TRANSFER("이체") }

/** Where an entry came from; only [MANUAL] exists in M1, notifications arrive in M2 [LEDG-02]. */
enum class MoneySource { MANUAL, NOTIFICATION, PASTE, MEMO }

/** One line of the household ledger [LEDG-01]. Amounts are whole won. */
data class MoneyEntry(
    val id: Long = 0,
    val date: LocalDate,
    val kind: MoneyKind,
    val amount: Long,
    val category: String,
    val note: String = "",
    val source: MoneySource = MoneySource.MANUAL,
)

/**
 * A booked job for field and freelance work [WORK-02]: who, where, when and for how much, with
 * the deposit (선금) and the rest (잔금) tracked separately because that is how such work is paid.
 */
data class Job(
    val id: Long = 0,
    val date: LocalDate,
    val title: String,
    val client: String = "",
    val place: String = "",
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val amount: Long = 0,
    val deposit: Long = 0,
    val depositPaid: Boolean = false,
    val balancePaid: Boolean = false,
    /** Minutes before [start] to remind; null for no reminder. */
    val remindMinutes: Int? = null,
    val note: String = "",
) {
    val balance: Long get() = (amount - deposit).coerceAtLeast(0)
    val received: Long get() = (if (depositPaid) deposit.coerceAtMost(amount) else 0) + (if (balancePaid) balance else 0)
    val unpaid: Long get() = (amount - received).coerceAtLeast(0)
}

/** A shop's day [WORK-03]: what came in and what the day cost. */
data class DailySales(val date: LocalDate, val sales: Long, val costs: Long = 0, val note: String = "") {
    val net: Long get() = sales - costs
}

object MoneyCategories {
    /** Spending categories; the first eleven match the 살림왕 parser so M2 can fill them [LEDG-02]. */
    val expense = listOf("식비", "카페", "편의점", "배달", "장보기·쇼핑", "교통", "자동차", "의료", "통신", "공과금", "구독",
        "주거", "경조사", "교육", "여가", "기타")
    val income = listOf(SALARY, "부수입", "용돈", "환급", "이자", "기타")
    val transfer = listOf("저축", "카드값", "내 계좌", "기타")

    fun of(kind: MoneyKind) = when (kind) { MoneyKind.EXPENSE -> expense; MoneyKind.INCOME -> income; MoneyKind.TRANSFER -> transfer }

    const val SALARY = "급여"
}

data class CategoryTotal(val category: String, val amount: Long, val count: Int)

/**
 * One month of money for the money tab [WORK-04][LEDG-01]. Income adds up each source the user's
 * work produces; when the user records the salary actually paid (분류 급여) it replaces the
 * engine's estimate so the same pay is never counted twice.
 */
data class MonthMoney(
    val month: YearMonth,
    val payEstimate: Long?,
    val payRecorded: Long,
    val jobEarned: Long,
    val jobReceived: Long,
    val jobCount: Int,
    val sales: Long,
    val salesCosts: Long,
    val salesDays: Int,
    val otherIncome: Long,
    val expenses: Long,
    val expenseByCategory: List<CategoryTotal>,
) {
    /** The engine's estimate only counts until a real salary is written down. */
    val payCounted: Long get() = if (payRecorded > 0) payRecorded else payEstimate ?: 0
    val salesNet: Long get() = sales - salesCosts
    val income: Long get() = payCounted + jobEarned + salesNet + otherIncome
    val balance: Long get() = income - expenses
}

data class DayMoney(val income: Long, val expense: Long, val jobs: Int, val unpaid: Long) {
    val isEmpty: Boolean get() = income == 0L && expense == 0L && jobs == 0
}

object MoneyCalculator {
    fun month(
        month: YearMonth,
        entries: List<MoneyEntry>,
        jobs: List<Job>,
        sales: List<DailySales>,
        payEstimate: Long? = null,
    ): MonthMoney {
        val inMonth = { d: LocalDate -> YearMonth.from(d) == month }
        val e = entries.filter { inMonth(it.date) }
        val j = jobs.filter { inMonth(it.date) }
        val s = sales.filter { inMonth(it.date) }
        val incomes = e.filter { it.kind == MoneyKind.INCOME }
        val (salary, other) = incomes.partition { it.category == MoneyCategories.SALARY }
        val spent = e.filter { it.kind == MoneyKind.EXPENSE }
        return MonthMoney(
            month = month,
            payEstimate = payEstimate,
            payRecorded = salary.sumOf { it.amount },
            jobEarned = j.sumOf { it.amount },
            jobReceived = j.sumOf { it.received },
            jobCount = j.size,
            sales = s.sumOf { it.sales },
            salesCosts = s.sumOf { it.costs },
            salesDays = s.count { it.sales != 0L || it.costs != 0L },
            otherIncome = other.sumOf { it.amount },
            expenses = spent.sumOf { it.amount },
            expenseByCategory = spent.groupBy { it.category }
                .map { (c, list) -> CategoryTotal(c, list.sumOf { it.amount }, list.size) }
                .sortedWith(compareByDescending<CategoryTotal> { it.amount }.thenBy { it.category }),
        )
    }

    /** Money per day for calendar cells; days with nothing are left out. */
    fun days(month: YearMonth, entries: List<MoneyEntry>, jobs: List<Job>, sales: List<DailySales>): Map<LocalDate, DayMoney> {
        val inMonth = { d: LocalDate -> YearMonth.from(d) == month }
        val e = entries.filter { inMonth(it.date) }.groupBy { it.date }
        val j = jobs.filter { inMonth(it.date) }.groupBy { it.date }
        val s = sales.filter { inMonth(it.date) }.associateBy { it.date }
        val out = HashMap<LocalDate, DayMoney>()
        for (date in e.keys + j.keys + s.keys) {
            val de = e[date].orEmpty()
            val dj = j[date].orEmpty()
            val day = DayMoney(
                income = de.filter { it.kind == MoneyKind.INCOME }.sumOf { it.amount } + dj.sumOf { it.amount } + (s[date]?.net ?: 0),
                expense = de.filter { it.kind == MoneyKind.EXPENSE }.sumOf { it.amount },
                jobs = dj.size,
                unpaid = dj.sumOf { it.unpaid },
            )
            if (!day.isEmpty) out[date] = day
        }
        return out
    }

    /** Jobs not fully paid, oldest first, up to [until] (future jobs are not owed yet) [WORK-02]. */
    fun unpaid(jobs: List<Job>, until: LocalDate): List<Job> =
        jobs.filter { it.unpaid > 0 && !it.date.isAfter(until) }.sortedWith(compareBy({ it.date }, { it.id }))
}

/** Everything the user wrote down about money, as screens observe it. */
data class MoneyBook(
    val entries: List<MoneyEntry> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val sales: List<DailySales> = emptyList(),
) {
    fun month(month: YearMonth, payEstimate: Long? = null) = MoneyCalculator.month(month, entries, jobs, sales, payEstimate)
    fun days(month: YearMonth) = MoneyCalculator.days(month, entries, jobs, sales)
    fun on(date: LocalDate) = DayBook(entries.filter { it.date == date }, jobs.filter { it.date == date }, sales.firstOrNull { it.date == date })

    companion object { val EMPTY = MoneyBook() }
}

data class DayBook(val entries: List<MoneyEntry>, val jobs: List<Job>, val sales: DailySales?)
