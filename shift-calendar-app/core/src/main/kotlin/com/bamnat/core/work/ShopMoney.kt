package com.bamnat.core.work

import com.bamnat.core.money.DailySales
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * [SHOP-02][SHOP-03][SHOP-04][SHOP-05] A shop calendar's money settings: what the card company
 * keeps and how many business days its money takes, the cash the till opens with, the month's
 * sales target, and how the shop pays 부가세 (일반 or 간이). Everything is 참고용.
 */
data class ShopMoney(
    /** Card fee in hundredths of a percent: 150 is 1.5%. */
    val cardFeeBp: Int = 150,
    /** Business days from a card sale to its money in the account. */
    val settleDays: Int = 2,
    /** [SHOP-03] Cash in the till when the shop opens (시작 시재). */
    val openingCash: Long = 0,
    /** [SHOP-05] This month's sales target; 0 for none. */
    val monthlyTarget: Long = 0,
    /** [SHOP-04] 간이과세자: 부가세 is sales × the trade's value-added rate × 10%. */
    val simpleTax: Boolean = false,
    /** [SHOP-04] 업종별 부가가치율 for 간이과세, in percent. */
    val valueAddedPercent: Int = 15,
) {
    /** "1.5%" */
    val feeLabel: String get() = (cardFeeBp / 100.0).let { if (it == it.toLong().toDouble()) "${it.toLong()}%" else "%.2f".format(it).trimEnd('0') + "%" }
}

/** [SHOP-01] A month's sales by how they were paid. Days written only as a total count as 나누지 않음. */
data class SalesSplit(val card: Long, val cash: Long, val transfer: Long, val delivery: Long, val unsplit: Long) {
    val total: Long get() = card + cash + transfer + delivery + unsplit
    /** The parts with their names, largest first, leaving out empty ones. */
    val parts: List<Pair<String, Long>> get() =
        listOf("카드" to card, "현금" to cash, "계좌이체" to transfer, "배달앱" to delivery, "나누지 않음" to unsplit)
            .filter { it.second > 0 }.sortedByDescending { it.second }

    companion object {
        fun of(month: YearMonth, sales: List<DailySales>): SalesSplit {
            val days = sales.filter { YearMonth.from(it.date) == month }
            return SalesSplit(
                card = days.sumOf { it.card }, cash = days.sumOf { it.cash }, transfer = days.sumOf { it.transfer }, delivery = days.sumOf { it.delivery },
                unsplit = days.sumOf { (it.sales - it.byMethod).coerceAtLeast(0) },
            )
        }
    }
}

/**
 * [SHOP-02] Card sales come in a few business days later, less the card fee. Each day's card sales
 * become one expected deposit; the user ticks it 입금 확인 ([DailySales.cardSettled]), or a bank
 * notification of a card company's deposit ticks it ([match]).
 */
object CardSettlement {
    data class Expected(val saleDate: LocalDate, val payDate: LocalDate, val card: Long, val amount: Long, val settled: Boolean)

    /** [days] business days after [sale]: weekends and public holidays are skipped. */
    fun payDate(sale: LocalDate, days: Int, isHoliday: (LocalDate) -> Boolean): LocalDate {
        var d = sale
        var left = days.coerceAtLeast(0)
        while (left > 0) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && !isHoliday(d)) left--
        }
        return d
    }

    /** What reaches the account for [card] won of sales at [feeBp] (1.5% is 150), the fee rounded to the won. */
    fun afterFee(card: Long, feeBp: Int): Long = card - (card * feeBp.coerceAtLeast(0) + 5_000) / 10_000

    /** Every day with card sales, as the deposit it should bring. */
    fun expected(sales: List<DailySales>, rules: ShopMoney, isHoliday: (LocalDate) -> Boolean): List<Expected> =
        sales.filter { it.card > 0 }.sortedBy { it.date }
            .map { Expected(it.date, payDate(it.date, rules.settleDays, isHoliday), it.card, afterFee(it.card, rules.cardFeeBp), it.cardSettled) }

    /** Deposits still to come up to [until], soonest first, and any already late. */
    fun waiting(expected: List<Expected>, until: LocalDate): List<Expected> = expected.filter { !it.settled && !it.payDate.isAfter(until) }

    /**
     * [SHOP-08] The expected deposits a card company's deposit of [amount] on [date] most likely is:
     * one day's whose amount is within 0.5% (card fees differ a little from the setting), or every
     * day due on one pay date when they add up. Only deposits due from five days before to two days
     * after [date] are looked at; nothing is returned when nothing fits.
     */
    fun match(amount: Long, date: LocalDate, expected: List<Expected>): List<Expected> {
        if (amount <= 0) return emptyList()
        val open = expected.filter { !it.settled && !it.payDate.isBefore(date.minusDays(5)) && !it.payDate.isAfter(date.plusDays(2)) }
        fun close(a: Long, b: Long) = kotlin.math.abs(a - b) <= maxOf(100L, b / 200)
        val near = compareBy<Expected> { kotlin.math.abs(it.payDate.toEpochDay() - date.toEpochDay()) }
        open.filter { close(amount, it.amount) }.sortedWith(near).firstOrNull()?.let { return listOf(it) }
        return open.groupBy { it.payDate }.entries.sortedWith(compareBy { kotlin.math.abs(it.key.toEpochDay() - date.toEpochDay()) })
            .firstOrNull { (_, list) -> list.size > 1 && close(amount, list.sumOf { it.amount }) }?.value.orEmpty()
    }
}

/** [SHOP-03] 시재: the cash that should be in the till at closing, against what was counted. */
object CashDrawer {
    data class Check(val expected: Long, val counted: Long?) {
        /** Counted minus expected: above 0 is more cash than there should be. */
        val difference: Long? get() = counted?.let { it - expected }
    }

    /**
     * Opening cash + the day's cash sales − cash paid out that day ([cashSpent], from the ledger lines
     * paid in cash). Without a cash split the day's whole sales are taken as cash only when nothing
     * was split at all.
     */
    fun check(day: DailySales, openingCash: Long, cashSpent: Long = 0): Check {
        val cashSales = if (day.byMethod > 0) day.cash else day.sales
        return Check(openingCash + cashSales - cashSpent, day.cashCounted)
    }

    /** Ledger lines of [date] paid from a cash account ([cashAccounts]): what left the till. */
    fun cashSpent(entries: List<MoneyEntry>, date: LocalDate, cashAccounts: Set<Long>): Long =
        entries.filter { it.date == date && it.kind == MoneyKind.EXPENSE && it.accountId in cashAccounts }.sumOf { it.amount }
}

/**
 * [SHOP-04] 부가세, roughly: 일반과세 (sales − costs with a tax invoice or card/cash receipt) × 10/110,
 * by half years 1–6 (신고 7/25) and 7–12 (신고 다음 해 1/25); 간이과세 sales × the trade's value-added
 * rate × 10%, once for the whole year (신고 다음 해 1/25), and nothing to pay on a year's sales under
 * [SIMPLE_EXEMPT] (부가가치세법 제69조). 대략·참고용: the 신고 itself works it out properly.
 */
object VatEstimate {
    /** [SHOP-04] 간이과세 납부의무 면제: a year's sales (공급대가) under 4,800만 원. */
    const val SIMPLE_EXEMPT = 48_000_000L

    data class Half(
        val year: Int,
        /** 1 for January–June, 2 for July–December, 0 for 간이과세's whole year. */
        val half: Int,
        val sales: Long,
        val expenses: Long,
        /** Costs with a proof that carries VAT (세금계산서·카드 영수증·현금영수증). */
        val provenExpenses: Long,
        val vat: Long,
        val due: LocalDate,
        /** 간이과세 with the year's sales so far under [SIMPLE_EXEMPT]: no 부가세 to pay. */
        val exempt: Boolean = false,
    ) {
        val from: LocalDate get() = LocalDate.of(year, if (half == 2) 7 else 1, 1)
        val to: LocalDate get() = if (half == 1) LocalDate.of(year, 6, 30) else LocalDate.of(year, 12, 31)
    }

    /** Proofs a cost's VAT can be taken off with. */
    val DEDUCTIBLE = setOf("세금계산서", "카드 영수증", "현금영수증")

    /** The half year [date] falls in, or for 간이과세 its year. */
    fun of(date: LocalDate, sales: List<DailySales>, entries: List<MoneyEntry>, rules: ShopMoney): Half {
        val half = if (rules.simpleTax) 0 else if (date.monthValue <= 6) 1 else 2
        val from = LocalDate.of(date.year, if (half == 2) 7 else 1, 1)
        val to = if (half == 1) LocalDate.of(date.year, 6, 30) else LocalDate.of(date.year, 12, 31)
        val inHalf = { d: LocalDate -> !d.isBefore(from) && !d.isAfter(to) }
        val s = sales.filter { inHalf(it.date) }
        val spent = entries.filter { inHalf(it.date) && it.kind == MoneyKind.EXPENSE }
        val total = s.sumOf { it.sales }
        val proven = spent.filter { it.proof in DEDUCTIBLE }.sumOf { it.amount }
        val exempt = rules.simpleTax && total < SIMPLE_EXEMPT
        val vat = if (exempt) 0 else if (rules.simpleTax) total * rules.valueAddedPercent.coerceIn(0, 100) / 1_000
        else ((total - proven).coerceAtLeast(0)) * 10 / 110
        return Half(
            year = date.year, half = half, sales = total, expenses = s.sumOf { it.costs } + spent.sumOf { it.amount },
            provenExpenses = proven, vat = vat,
            due = if (half == 1) LocalDate.of(date.year, 7, 25) else LocalDate.of(date.year + 1, 1, 25),
            exempt = exempt,
        )
    }
}

/** [SHOP-05] The month's sales target: how far along it is and what each open day left must sell. */
object SalesTarget {
    data class Progress(val target: Long, val achieved: Long, val openDaysLeft: Int) {
        val remaining: Long get() = (target - achieved).coerceAtLeast(0)
        /** Whole percent reached, at most 999. */
        val percent: Int get() = if (target <= 0) 0 else (achieved * 100 / target).coerceIn(0, 999).toInt()
        /** What each open day left must sell; 0 when reached or no open day is left. */
        val perDay: Long get() = if (openDaysLeft <= 0) 0 else (remaining + openDaysLeft - 1) / openDaysLeft
        val reached: Boolean get() = target in 1..achieved
    }

    /**
     * Progress in [month] as of [today]. Open days left count from today (unless today's sales are
     * already written) to the month's end, by [isOpen]; a past month has none, a coming month all of them.
     */
    fun of(target: Long, month: YearMonth, sales: List<DailySales>, today: LocalDate, isOpen: (LocalDate) -> Boolean): Progress? {
        if (target <= 0) return null
        val days = sales.filter { YearMonth.from(it.date) == month }
        val achieved = days.sumOf { it.sales }
        val start = when {
            month.isBefore(YearMonth.from(today)) -> null
            month.isAfter(YearMonth.from(today)) -> month.atDay(1)
            days.any { it.date == today && it.sales > 0 } -> today.plusDays(1)
            else -> today
        }
        val left = if (start == null) 0
        else generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(month.atEndOfMonth()) }.count(isOpen)
        return Progress(target, achieved, left)
    }
}
