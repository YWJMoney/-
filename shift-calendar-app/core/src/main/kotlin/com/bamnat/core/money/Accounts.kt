package com.bamnat.core.money

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** [LEDG-08] What kind of place money is kept or paid from. */
enum class AccountType(val label: String, val hint: String) {
    CASH("현금", "지갑에 있는 돈"),
    BANK("통장", "월급 통장, 생활비 통장"),
    CHECK("체크카드", "쓰면 바로 연결한 통장에서 나가요"),
    CREDIT("신용카드", "쓴 돈이 결제일에 한 번에 나가요"),
    PAY("페이·충전", "페이 머니, 교통카드 충전"),
    OTHER("기타", "적금, 상품권 등"),
}

/**
 * [LEDG-08] Cash, a bank account, a card or a pay balance. A credit card's spending leaves on its
 * 결제일 ([payDay]) for the days up to its 마감일 ([closeDay]); a check card's leaves its linked
 * account ([payFromId]) at once. Accounts that hold money keep a balance from [opening].
 */
data class MoneyAccount(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    /** Last four digits of the card, so payment texts find it by themselves. */
    val last4: String = "",
    /** A credit card's 결제일, 1..31 (past the month's end is its last day). */
    val payDay: Int? = null,
    /** The last day a bill covers (마감일); null for the month before's last day, the usual 1일~말일. */
    val closeDay: Int? = null,
    /** The account a card's money leaves from: a check card's at once, a credit card's bill on its day. */
    val payFromId: Long? = null,
    /** What was in it when the user began counting here. */
    val opening: Long = 0,
    val color: Int = 0,
    val order: Int = 0,
    /** Put away: kept for its old lines but not offered any more. */
    val hidden: Boolean = false,
    /** A credit card's bill three days ahead and on the day, like the fixed costs [LEDG-05]. */
    val remind: Boolean = true,
) {
    val isCredit: Boolean get() = type == AccountType.CREDIT
    val isCard: Boolean get() = type == AccountType.CREDIT || type == AccountType.CHECK
    /** Money sits in it, so it has a balance; a card only moves money from elsewhere. */
    val keepsBalance: Boolean get() = !isCard
    val hasBill: Boolean get() = isCredit && payDay != null

    /** "신한 Deep (1234)" */
    val label: String get() = if (last4.isNotBlank() && !name.contains(last4)) "$name ($last4)" else name

    companion object {
        const val MAX_NAME = 20
    }
}

object MoneyAccounts {
    /**
     * [LEDG-08] The account a line belongs to: the one picked for it, or else the card whose last
     * four digits the payment text carried, so lines read before the card was added find it too.
     */
    fun of(e: MoneyEntry, accounts: List<MoneyAccount>): Long? {
        e.accountId?.let { id -> if (accounts.any { it.id == id }) return id }
        val l4 = e.cardLast4.takeIf { it.length == 4 } ?: return null
        val same = accounts.filter { it.last4 == l4 }
        return (same.firstOrNull { it.isCard } ?: same.firstOrNull())?.id
    }

    /** The account a payment text belongs to when it is saved, or null when no card matches. */
    fun match(cardLast4: String, accounts: List<MoneyAccount>): Long? =
        of(MoneyEntry(date = LocalDate.MIN, kind = MoneyKind.EXPENSE, amount = 0, category = "", cardLast4 = cardLast4), accounts)

    /**
     * [LEDG-08] What is in each account that holds money: its opening amount, plus money in, less
     * money out. A check card's spending leaves its linked account; a credit card's only leaves on
     * its bill, which is a transfer to the card. Transfers move money and are never spending.
     */
    fun balances(accounts: List<MoneyAccount>, entries: List<MoneyEntry>): Map<Long, Long> {
        val byId = accounts.associateBy { it.id }
        val out = accounts.filter { it.keepsBalance }.associate { it.id to it.opening }.toMutableMap()
        fun holder(id: Long?): Long? {
            val a = byId[id ?: return null] ?: return null
            return when {
                a.keepsBalance -> a.id
                a.type == AccountType.CHECK -> a.payFromId?.takeIf { byId[it]?.keepsBalance == true }
                else -> null
            }
        }
        for (e in entries) {
            val from = holder(of(e, accounts))
            when (e.kind) {
                MoneyKind.EXPENSE -> from?.let { out.merge(it, -e.amount, Long::plus) }
                MoneyKind.INCOME -> from?.let { out.merge(it, e.amount, Long::plus) }
                MoneyKind.TRANSFER -> {
                    from?.let { out.merge(it, -e.amount, Long::plus) }
                    holder(e.toAccountId)?.let { out.merge(it, e.amount, Long::plus) }
                }
            }
        }
        return out
    }

    /** [LEDG-13] The account a new line of [kind] starts on: the one the last such line used. */
    fun lastUsed(entries: List<MoneyEntry>, kind: MoneyKind, accounts: List<MoneyAccount>): Long? {
        val live = accounts.filter { !it.hidden }.map { it.id }.toSet()
        return entries.asSequence().filter { it.kind == kind && it.source == MoneySource.MANUAL }
            .sortedWith(compareByDescending<MoneyEntry> { it.date }.thenByDescending { it.id })
            .mapNotNull { it.accountId }.firstOrNull { it in live }
    }
}

/**
 * [LEDG-08] A credit card's bills. The bill paid in a month covers the days up to that cycle's
 * 마감일: in the month before when the 마감일 is on or after the 결제일 (결제일 14일, 1일~말일 쓴 것),
 * or in the same month when it comes earlier (결제일 25일, 11일~10일). Installments (할부) are
 * spread over that many bills, the remainder in the first.
 */
object CardCycle {
    data class Bill(val card: MoneyAccount, val payDate: LocalDate, val from: LocalDate, val to: LocalDate, val amount: Long, val count: Int) {
        val month: YearMonth get() = YearMonth.from(payDate)
    }

    private fun dayIn(m: YearMonth, day: Int): LocalDate = m.atDay(day.coerceIn(1, m.lengthOfMonth()))
    private fun close(card: MoneyAccount): Int = (card.closeDay ?: 31).coerceIn(1, 31)
    private fun pay(card: MoneyAccount): Int = (card.payDay ?: 1).coerceIn(1, 31)

    /** The days a bill paid in [payMonth] covers. */
    fun period(card: MoneyAccount, payMonth: YearMonth): Pair<LocalDate, LocalDate> {
        val end = if (close(card) < pay(card)) payMonth else payMonth.minusMonths(1)
        return dayIn(end.minusMonths(1), close(card)).plusDays(1) to dayIn(end, close(card))
    }

    /** The month whose bill a payment made on [date] lands on (its first bill, for installments). */
    fun billMonth(card: MoneyAccount, date: LocalDate): YearMonth {
        val m = YearMonth.from(date)
        val end = if (!date.isAfter(dayIn(m, close(card)))) m else m.plusMonths(1)
        return if (close(card) < pay(card)) end else end.plusMonths(1)
    }

    fun payDate(card: MoneyAccount, payMonth: YearMonth): LocalDate = dayIn(payMonth, pay(card))

    /** What of [e] is paid in [payMonth]. */
    fun portion(e: MoneyEntry, first: YearMonth, payMonth: YearMonth): Long {
        val n = e.installments.coerceIn(1, 60)
        val k = ChronoUnit.MONTHS.between(first, payMonth)
        if (k < 0 || k >= n) return 0
        if (n == 1 || e.amount <= 0) return if (k == 0L) e.amount else 0
        val each = e.amount / n
        return if (k == 0L) e.amount - each * (n - 1) else each
    }

    fun bill(card: MoneyAccount, payMonth: YearMonth, entries: List<MoneyEntry>, accounts: List<MoneyAccount>): Bill {
        var amount = 0L
        var count = 0
        for (e in entries) {
            if (e.kind != MoneyKind.EXPENSE || MoneyAccounts.of(e, accounts) != card.id) continue
            val p = portion(e, billMonth(card, e.date), payMonth)
            if (p != 0L) { amount += p; count++ }
        }
        val (from, to) = period(card, payMonth)
        return Bill(card, payDate(card, payMonth), from, to, amount, count)
    }

    /** The first bill on or after [today]. */
    fun next(card: MoneyAccount, today: LocalDate, entries: List<MoneyEntry>, accounts: List<MoneyAccount>): Bill {
        val m = YearMonth.from(today)
        return bill(card, if (payDate(card, m).isBefore(today)) m.plusMonths(1) else m, entries, accounts)
    }

    /** The bill being built now: the one today's spending will be on. */
    fun current(card: MoneyAccount, today: LocalDate, entries: List<MoneyEntry>, accounts: List<MoneyAccount>): Bill =
        bill(card, billMonth(card, today), entries, accounts)

    /** Whether the bill of [payMonth] was paid: a transfer to the card, or its 카드값 line, within two weeks of the day. */
    fun paid(card: MoneyAccount, payMonth: YearMonth, entries: List<MoneyEntry>): Boolean {
        val day = payDate(card, payMonth)
        return entries.any { e ->
            e.kind == MoneyKind.TRANSFER && kotlin.math.abs(ChronoUnit.DAYS.between(day, e.date)) <= PAID_WINDOW_DAYS &&
                (e.toAccountId == card.id || (e.source == MoneySource.FIXED && e.merchant == card.name))
        }
    }

    private const val PAID_WINDOW_DAYS = 14L
}
