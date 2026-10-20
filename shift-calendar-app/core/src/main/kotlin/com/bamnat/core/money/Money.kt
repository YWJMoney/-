package com.bamnat.core.money

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** [LEDG-01] Money moving out, in, or between the user's own accounts (not counted). */
enum class MoneyKind(val label: String) { EXPENSE("지출"), INCOME("수입"), TRANSFER("이체") }

/** Where an entry came from: typed, a payment notification [LEDG-02], a pasted text [LEDG-03], a memo [WORK-05], a fixed cost [LEDG-05]. */
enum class MoneySource { MANUAL, NOTIFICATION, PASTE, MEMO, FIXED }

/** One line of the household ledger [LEDG-01]. Amounts are whole won. */
data class MoneyEntry(
    val id: Long = 0,
    val date: LocalDate,
    val kind: MoneyKind,
    val amount: Long,
    val category: String,
    val note: String = "",
    val source: MoneySource = MoneySource.MANUAL,
    /** Where the money went or came from (가맹점), read from a text or typed [LEDG-04]. */
    val merchant: String = "",
    val time: LocalTime? = null,
    val cardLast4: String = "",
    /** False for a notification the app wrote down by itself until the user has seen it [LEDG-02]. */
    val reviewed: Boolean = true,
    /** [LEDG-08] The account, card or cash the money left or reached; null when none was picked. */
    val accountId: Long? = null,
    /** [LEDG-08] A transfer's other end: the account or card the money went to. */
    val toAccountId: Long? = null,
    /** [LEDG-11] The user's own words about the line, apart from what a payment text said ([note]). */
    val memo: String = "",
    /** [LEDG-11] Labels such as 여행 or 회식, kept without '#'. */
    val tags: List<String> = emptyList(),
    /** [LEDG-11] One payment spread over several categories; empty when it is all [category]. */
    val parts: List<EntryPart> = emptyList(),
    /** [LEDG-08] Months a card payment is paid over (할부); 1 when paid at once. */
    val installments: Int = 1,
    /** [LEDG-09] The repeating line ([FixedCost]) this one was written for. */
    val fixedId: Long? = null,
    /** [FLD-08] What proves the cost (세금계산서, 카드 영수증, 현금영수증, 간이영수증), or empty. */
    val proof: String = "",
    /** [FLD-08] A receipt photo kept in the app's own folder, by file name; empty for none. */
    val receipt: String = "",
) {
    /** What a list shows as the line's name. */
    val title: String get() = merchant.ifBlank { note.ifBlank { memo.ifBlank { category } } }

    /**
     * [LEDG-11] What each category got of this line, signed like [amount]: the parts of a split
     * payment, or the whole amount under [category]. The pieces always add up to [amount].
     */
    fun shares(): List<Pair<String, Long>> {
        if (parts.isEmpty()) return listOf(category to amount)
        val sign = if (amount < 0) -1 else 1
        val whole = kotlin.math.abs(amount)
        val given = parts.filter { it.amount > 0 }
        val sum = given.sumOf { it.amount }
        // Parts that no longer add up (the amount was changed elsewhere) leave the difference with the main category.
        val fixed = if (sum == whole) given else given + EntryPart(category, whole - sum)
        return fixed.filter { it.amount != 0L }.groupBy { it.category }.map { (c, list) -> c to sign * list.sumOf { it.amount } }
    }

    /** [LEDG-11] What [c] got of this line; 0 when it has no part of it. */
    fun shareOf(c: String): Long = shares().filter { it.first == c }.sumOf { it.second }

    /** Spread over more than one category. */
    val isSplit: Boolean get() = shares().size > 1
}

/** [LEDG-11] One category's piece of a split payment, in whole won. */
data class EntryPart(val category: String, val amount: Long)

/**
 * A booked job for field and freelance work [WORK-02]: who, where, when and for how much, with
 * the deposit (선금) and the rest (잔금) tracked separately because that is how such work is paid.
 * A job may run several days (a bathroom takes three); its money belongs to the first day.
 */
data class Job(
    val id: Long = 0,
    val date: LocalDate,
    /** Last day of a job that runs several days; null for one day. */
    val endDate: LocalDate? = null,
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
    /** Materials and other costs of this job (자재비·인건비), so its profit is known. */
    val costs: Long = 0,
    /** Paid as 사업소득 with 3.3% withheld (freelance teaching, filming, many trades). */
    val withholding: Boolean = false,
    /** [FLD-03] Where the job stands: 가계약, 확정, 완료 or 취소; paid follows from the payments. */
    val status: JobStatus = JobStatus.CONFIRMED,
    /** [FLD-02] Days of work counted as 공수 (1, 0.5, 1.5 …) for day-rate work; null for a job paid as a whole. */
    val units: Double? = null,
    /** [FLD-09] When the money is promised; null follows the last day. */
    val due: LocalDate? = null,
) {
    val lastDate: LocalDate get() = endDate?.takeIf { it.isAfter(date) } ?: date
    val days: Int get() = (lastDate.toEpochDay() - date.toEpochDay()).toInt() + 1
    fun covers(d: LocalDate): Boolean = !d.isBefore(date) && !d.isAfter(lastDate)

    /** 3.3% withheld: 3% income tax and a tenth of it as local tax, each cut to 10 won as tax law does. */
    val withheld: Long get() = if (!withholding) 0 else Withholding.of(amount)

    /** What actually reaches the account; deposit and balance are parts of it. */
    val payable: Long get() = amount - withheld
    val balance: Long get() = (payable - deposit).coerceAtLeast(0)
    val received: Long get() = (if (depositPaid) deposit.coerceAtMost(payable) else 0) + (if (balancePaid) balance else 0)
    val unpaid: Long get() = (payable - received).coerceAtLeast(0)
    val profit: Long get() = payable - costs
}

/** [WORK-02] Withholding on 사업소득: 소득세 3% + 지방소득세 (소득세의 10%), each below 10 won dropped. */
object Withholding {
    fun of(amount: Long): Long {
        if (amount <= 0) return 0
        val income = amount * 3 / 100 / 10 * 10
        val local = income / 10 / 10 * 10
        return income + local
    }
}

/** A shop's day [WORK-03]: what came in and what the day cost. */
data class DailySales(
    val date: LocalDate,
    val sales: Long,
    val costs: Long = 0,
    val note: String = "",
    /** [SHOP-01] The day's sales by how they were paid; all 0 when only the total was written. */
    val card: Long = 0,
    val cash: Long = 0,
    val transfer: Long = 0,
    val delivery: Long = 0,
    /** [SHOP-03] The cash counted in the till at closing (시재); null when not counted. */
    val cashCounted: Long? = null,
    /** [SHOP-02] The card company has paid this day's card sales into the account. */
    val cardSettled: Boolean = false,
) {
    val net: Long get() = sales - costs
    /** [SHOP-01] Sales written by how they were paid. */
    val byMethod: Long get() = card + cash + transfer + delivery
}

/** [FLD-03] A job's steps. Cancelled jobs count for nothing. */
enum class JobStatus(val label: String, val code: Int) {
    TENTATIVE("가계약", 0), CONFIRMED("확정", 1), DONE("완료", 2), CANCELLED("취소", 3);

    companion object {
        fun fromCode(code: Int): JobStatus = entries.firstOrNull { it.code == code } ?: CONFIRMED
    }
}

object MoneyCategories {
    /**
     * Spending categories; the first eleven match the 살림왕 parser so M2 can fill them [LEDG-02].
     * [LEDG-14] 생활·의류·미용·보험·반려동물·육아 came in 1.15, before 기타, which stays last.
     */
    val expense = listOf("식비", "카페", "편의점", "배달", "장보기·쇼핑", "교통", "자동차", "의료", "통신", "공과금", "구독",
        "주거", "경조사", "교육", "여가", "생활", "의류·미용", "보험", "반려동물", "육아", OTHER)
    val income = listOf(SALARY, "부수입", "용돈", "상여", "환급", "이자", "판매", OTHER)
    val transfer = listOf("저축", "카드값", "내 계좌", "투자", "대출 상환", OTHER)

    fun of(kind: MoneyKind) = when (kind) { MoneyKind.EXPENSE -> expense; MoneyKind.INCOME -> income; MoneyKind.TRANSFER -> transfer }

    /**
     * [TYPE-08] The built-in categories of a calendar of [style]: field and shop work see their own
     * costs and income first (자재비·유류비, 재료비·임대료·카드수수료 …), then the household ones, so a
     * work calendar's money reads like a work book (근무유형별 시장조사 7절).
     */
    fun of(kind: MoneyKind, style: com.bamnat.core.work.WorkStyle?): List<String> {
        val work = when (style) {
            com.bamnat.core.work.WorkStyle.FIELD -> when (kind) {
                MoneyKind.EXPENSE -> FIELD_EXPENSE
                MoneyKind.INCOME -> FIELD_INCOME
                MoneyKind.TRANSFER -> emptyList()
            }
            com.bamnat.core.work.WorkStyle.BUSINESS -> when (kind) {
                MoneyKind.EXPENSE -> SHOP_EXPENSE
                MoneyKind.INCOME -> SHOP_INCOME
                MoneyKind.TRANSFER -> listOf("사장님 인출")
            }
            else -> emptyList()
        }
        if (work.isEmpty()) return of(kind)
        return (work + of(kind)).distinct().filter { it != OTHER } + OTHER
    }

    val FIELD_EXPENSE = listOf("자재비", "공구·장비", "유류비", "차량유지", "톨비·주차", "현장식대", "작업복·안전", "숙박", "외주·인건비", "플랫폼수수료", "소모품")
    val FIELD_INCOME = listOf("일감 수입", "일당", "선금", "잔금")
    val SHOP_EXPENSE = listOf("재료비", "포장·소모품", "임대료", "관리비", "인건비", "4대보험", "카드수수료", "배달앱수수료", "광고·마케팅", "장비·수리", "세금", "대출이자")
    val SHOP_INCOME = listOf("카드매출", "현금매출", "계좌이체", "배달앱매출")

    /**
     * [LEDG-14] The categories for [kind] as the user arranged them: their order and own names
     * first, hidden ones left out, then any built-in name they never touched. 기타 is always there,
     * last, so every line has somewhere to go.
     */
    fun of(kind: MoneyKind, edits: List<CategoryEdit>, style: com.bamnat.core.work.WorkStyle? = null): List<String> {
        val base = of(kind, style)
        val mine = edits.filter { it.kind == kind }.sortedWith(compareBy({ it.position }, { it.name }))
        if (mine.isEmpty()) return base
        val touched = mine.map { it.name }.toSet()
        val shown = mine.filter { !it.hidden }.map { it.name } + base.filter { it !in touched }
        return shown.filter { it != OTHER }.distinct() + OTHER
    }

    /** [LEDG-14] A name the user may give a category: trimmed, short, and nothing a CSV or list would trip over. */
    fun clean(name: String): String = name.replace(Regex("[\\t\\n\\r,#]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_NAME)

    const val SALARY = "급여"
    const val OTHER = "기타"
    const val MAX_NAME = 12
}

/** [LEDG-14] One category the user added, hid or moved, for one kind of line. */
data class CategoryEdit(val kind: MoneyKind, val name: String, val position: Int, val hidden: Boolean = false)

data class CategoryTotal(val category: String, val amount: Long, val count: Int)

/**
 * One month of money for the money tab [WORK-04][LEDG-01]. Income adds up each source the user's
 * work produces; when the user records the salary actually paid (분류 급여) it replaces the
 * engine's estimate so the same pay is never counted twice. A job counts only the deposit and
 * balance ticked as paid ([RECV-04]): money still owed is 받을 돈, not 들어온 돈, and the job's
 * costs go out with the other spending so an unpaid job never shows as negative income.
 */
data class MonthMoney(
    val month: YearMonth,
    val payEstimate: Long?,
    val payRecorded: Long,
    val jobEarned: Long,
    val jobReceived: Long,
    val jobCount: Int,
    /** Costs of this month's jobs (자재비 등), taken off the job income. */
    val jobCosts: Long = 0,
    /** 3.3% held back on this month's jobs; already out of [jobEarned]. */
    val jobWithheld: Long = 0,
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
    val jobProfit: Long get() = jobEarned - jobCosts
    /** [RECV-04] This month's jobs not yet paid; shown beside 들어온 돈, never inside it. */
    val jobOwed: Long get() = (jobEarned - jobReceived).coerceAtLeast(0)
    val income: Long get() = payCounted + jobReceived + salesNet + otherIncome
    /** Ledger spending plus the jobs' materials and other costs. */
    val spent: Long get() = expenses + jobCosts
    val balance: Long get() = income - spent
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
        // [LEDG-11] A split payment counts under each of its categories.
        val shares = spent.flatMap { it.shares() }
        return MonthMoney(
            month = month,
            payEstimate = payEstimate,
            payRecorded = salary.sumOf { it.amount },
            jobEarned = j.sumOf { it.payable },
            jobReceived = j.sumOf { it.received },
            jobCount = j.size,
            jobCosts = j.sumOf { it.costs },
            jobWithheld = j.sumOf { it.withheld },
            sales = s.sumOf { it.sales },
            salesCosts = s.sumOf { it.costs },
            salesDays = s.count { it.sales != 0L || it.costs != 0L },
            otherIncome = other.sumOf { it.amount },
            expenses = spent.sumOf { it.amount },
            expenseByCategory = shares.groupBy { it.first }
                .map { (c, list) -> CategoryTotal(c, list.sumOf { it.second }, list.size) }
                .sortedWith(compareByDescending<CategoryTotal> { it.amount }.thenBy { it.category }),
        )
    }

    /** Money per day for calendar cells; days with nothing are left out. A job's money shows on its first day, once paid [RECV-04]. */
    fun days(month: YearMonth, entries: List<MoneyEntry>, jobs: List<Job>, sales: List<DailySales>): Map<LocalDate, DayMoney> {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val inMonth = { d: LocalDate -> YearMonth.from(d) == month }
        val e = entries.filter { inMonth(it.date) }.groupBy { it.date }
        val s = sales.filter { inMonth(it.date) }.associateBy { it.date }
        val starts = jobs.filter { inMonth(it.date) }.groupBy { it.date }
        val running = HashMap<LocalDate, Int>()
        for (j in jobs) {
            if (j.lastDate.isBefore(first) || j.date.isAfter(last)) continue
            var d = maxOf(j.date, first)
            val end = minOf(j.lastDate, last)
            while (!d.isAfter(end)) { running.merge(d, 1, Int::plus); d = d.plusDays(1) }
        }
        val out = HashMap<LocalDate, DayMoney>()
        for (date in e.keys + s.keys + running.keys) {
            val de = e[date].orEmpty()
            val dj = starts[date].orEmpty()
            val day = DayMoney(
                income = de.filter { it.kind == MoneyKind.INCOME }.sumOf { it.amount } + dj.sumOf { it.received } + (s[date]?.net ?: 0),
                expense = de.filter { it.kind == MoneyKind.EXPENSE }.sumOf { it.amount } + dj.sumOf { it.costs },
                jobs = running[date] ?: 0,
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
    val fixed: List<FixedCost> = emptyList(),
    /** [RECV-01] Money others owe the user. */
    val receivables: List<Receivable> = emptyList(),
    /** [LEDG-08] Cash, bank accounts, cards and pay balances the user keeps apart. */
    val accounts: List<MoneyAccount> = emptyList(),
    /** [LEDG-06] Monthly budgets: the whole month's and each category's. */
    val budgets: List<MoneyBudget> = emptyList(),
    /** [LEDG-14] The user's own categories, hidden ones and their order. */
    val categories: List<CategoryEdit> = emptyList(),
    /** [TYPE-08] The calendar's kind of work, which picks its built-in categories. */
    val style: com.bamnat.core.work.WorkStyle? = null,
) {
    fun month(month: YearMonth, payEstimate: Long? = null) = MoneyCalculator.month(month, entries, jobs, sales, payEstimate)
    fun days(month: YearMonth) = MoneyCalculator.days(month, entries, jobs, sales)
    /** [RECV-02] Everything still owed, written down or left on a job. */
    fun owed(today: LocalDate) = Receivables.owed(receivables, jobs, today)
    fun on(date: LocalDate) = DayBook(entries.filter { it.date == date }, jobs.filter { it.covers(date) }, sales.firstOrNull { it.date == date })

    /** [LEDG-14] The categories offered for [kind], in the user's order. */
    fun categoriesOf(kind: MoneyKind): List<String> = MoneyCategories.of(kind, categories, style)

    /** [LEDG-08] The accounts offered in pickers and lists, in the user's order. */
    val activeAccounts: List<MoneyAccount> get() = accounts.filter { !it.hidden }.sortedWith(compareBy({ it.order }, { it.id }))

    fun account(id: Long?): MoneyAccount? = id?.let { i -> accounts.firstOrNull { it.id == i } }

    companion object {
        val EMPTY = MoneyBook()

        /**
         * [MCAL-06][LEDG-08] Several calendars' money as one book, for the notices. Each calendar
         * numbers its accounts from 1, so every book after the first has its account ids moved out
         * of the way first; a card's bill then still adds up only its own calendar's payments.
         */
        fun merge(books: List<MoneyBook>): MoneyBook {
            books.singleOrNull()?.let { return it }
            val moved = books.mapIndexed { i, b -> if (i == 0) b else b.shiftAccounts(i * ACCOUNT_ID_STEP) }
            return MoneyBook(
                moved.flatMap { it.entries }, moved.flatMap { it.jobs }, moved.flatMap { it.sales }, moved.flatMap { it.fixed },
                moved.flatMap { it.receivables }, moved.flatMap { it.accounts },
            )
        }

        private const val ACCOUNT_ID_STEP = 1_000_000_000L

        private fun MoneyBook.shiftAccounts(by: Long): MoneyBook {
            val m = { id: Long? -> id?.plus(by) }
            return copy(
                entries = entries.map { it.copy(accountId = m(it.accountId), toAccountId = m(it.toAccountId), fixedId = m(it.fixedId)) },
                fixed = fixed.map { it.copy(id = it.id + by, accountId = m(it.accountId)) },
                accounts = accounts.map { it.copy(id = it.id + by, payFromId = m(it.payFromId)) },
            )
        }
    }
}

data class DayBook(val entries: List<MoneyEntry>, val jobs: List<Job>, val sales: DailySales?)
