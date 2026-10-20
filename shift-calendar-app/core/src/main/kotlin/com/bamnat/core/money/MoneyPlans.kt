package com.bamnat.core.money

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** [LEDG-09] How often a repeating line comes round. */
enum class Repeat(val label: String) { MONTH("매달"), WEEK("매주"), YEAR("매년") }

/**
 * [LEDG-05] Money that leaves on the same day every month: rent, insurance, phone, the card bill.
 * A card bill is counted as a transfer (카드값), because the card's payments are already expenses.
 * [LEDG-09] Now any repeating line: income too (월급, 용돈), every week or year as well as every
 * month, from one account, between a first and a last day, and written down by itself on its day
 * when [auto] is on.
 */
data class FixedCost(
    val id: Long = 0,
    val name: String,
    val amount: Long,
    /** Day of the month, 1..31 (a day past the month's end means its last day, 말일); for [Repeat.WEEK] 1 (월)..7 (일). */
    val day: Int,
    val kind: MoneyKind = MoneyKind.EXPENSE,
    val category: String = "주거",
    val remind: Boolean = true,
    val repeat: Repeat = Repeat.MONTH,
    /** For [Repeat.YEAR], the month, 1..12. */
    val monthOfYear: Int = 1,
    val accountId: Long? = null,
    /** Written into the ledger by itself on its day, instead of waiting for 나갔어요. */
    val auto: Boolean = false,
    /** First and last day it repeats on; null for no limit. */
    val start: LocalDate? = null,
    val end: LocalDate? = null,
) {
    fun dueIn(month: YearMonth): LocalDate = month.atDay(day.coerceIn(1, month.lengthOfMonth()))

    private fun active(d: LocalDate) = (start == null || !d.isBefore(start)) && (end == null || !d.isAfter(end))

    /** [LEDG-09] Every day it falls on from [from] to [to], within its first and last day. */
    fun occurrences(from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val days = when (repeat) {
            Repeat.MONTH -> generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }
                .map { dueIn(it) }.toList()
            Repeat.WEEK -> generateSequence(from.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day.coerceIn(1, 7))))) { it.plusWeeks(1) }
                .takeWhile { !it.isAfter(to) }.toList()
            Repeat.YEAR -> (from.year..to.year).map { y -> YearMonth.of(y, monthOfYear.coerceIn(1, 12)).let { m -> m.atDay(day.coerceIn(1, m.lengthOfMonth())) } }
        }
        return days.filter { !it.isBefore(from) && !it.isAfter(to) && active(it) }
    }

    /** [LEDG-09] The days one payment covers, so a line written a little early or late still counts once. */
    fun periodOf(date: LocalDate): Pair<LocalDate, LocalDate> = when (repeat) {
        Repeat.MONTH -> date.withDayOfMonth(1) to date.withDayOfMonth(date.lengthOfMonth())
        Repeat.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
        Repeat.YEAR -> date.withDayOfYear(1) to date.withDayOfYear(date.lengthOfYear())
    }

    /** "매달 25일", "매달 말일", "매주 월요일", "매년 3월 2일" */
    val schedule: String get() = when (repeat) {
        Repeat.MONTH -> "매달 " + if (day >= 31) "말일" else "${day}일"
        Repeat.WEEK -> "매주 " + WEEKDAYS[day.coerceIn(1, 7) - 1] + "요일"
        Repeat.YEAR -> "매년 ${monthOfYear}월 " + if (day >= 31) "말일" else "${day}일"
    }

    companion object {
        val WEEKDAYS = listOf("월", "화", "수", "목", "금", "토", "일")
    }
}

/** A payment due on [date]; [cardId] marks a credit card's bill worked out from its spending [LEDG-08]. */
data class Upcoming(val cost: FixedCost, val date: LocalDate, val daysLeft: Int, val cardId: Long? = null)

object UpcomingPayments {
    /** Whether this month's payment of [cost] is already written down. */
    fun recorded(cost: FixedCost, month: YearMonth, entries: List<MoneyEntry>): Boolean = recorded(cost, cost.dueIn(month), entries)

    /** [LEDG-09] Whether the payment of [cost] due on [date] is written down: a line for it in the same month, week or year. */
    fun recorded(cost: FixedCost, date: LocalDate, entries: List<MoneyEntry>): Boolean {
        val (from, to) = cost.periodOf(date)
        return entries.any { e ->
            !e.date.isBefore(from) && !e.date.isAfter(to) &&
                (e.fixedId == cost.id || (e.fixedId == null && e.source == MoneySource.FIXED && e.merchant == cost.name))
        }
    }

    /**
     * [LEDG-08] A credit card's bill as a payment, when the card has a 결제일. A 카드값 fixed cost
     * with the card's own name gives way to it, so the bill is not listed twice.
     */
    private fun cardBills(accounts: List<MoneyAccount>, entries: List<MoneyEntry>, from: LocalDate, to: LocalDate): List<Upcoming> =
        accounts.filter { it.hasBill && !it.hidden }.flatMap { card ->
            generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }.mapNotNull { m ->
                val day = CardCycle.payDate(card, m)
                if (day.isBefore(from) || day.isAfter(to) || CardCycle.paid(card, m, entries)) return@mapNotNull null
                val bill = CardCycle.bill(card, m, entries, accounts)
                if (bill.amount <= 0) null else Upcoming(cardCost(card, bill.amount), day, ChronoUnit.DAYS.between(from, day).toInt(), card.id)
            }.toList()
        }

    /** The 카드값 line a card's bill is written as. */
    fun cardCost(card: MoneyAccount, amount: Long) = FixedCost(
        id = -card.id, name = card.name, amount = amount, day = card.payDay ?: 1, kind = MoneyKind.TRANSFER, category = CARD_BILL,
        remind = card.remind, accountId = card.payFromId,
    )

    private fun replacedByCard(c: FixedCost, accounts: List<MoneyAccount>) =
        c.kind == MoneyKind.TRANSFER && accounts.any { it.hasBill && !it.hidden && it.name == c.name }

    /** Payments due from [today] for [days] days that are not yet recorded, soonest first. */
    fun within(
        costs: List<FixedCost>, entries: List<MoneyEntry>, today: LocalDate, days: Int = 7, accounts: List<MoneyAccount> = emptyList(),
    ): List<Upcoming> {
        val to = today.plusDays(days.toLong())
        val fixed = costs.filterNot { replacedByCard(it, accounts) }.flatMap { c ->
            c.occurrences(today, to).filter { !recorded(c, it, entries) }.map { d -> Upcoming(c, d, ChronoUnit.DAYS.between(today, d).toInt()) }
        }
        return (fixed + cardBills(accounts, entries, today, to)).sortedWith(compareBy({ it.date }, { it.cost.kind != MoneyKind.INCOME }, { it.cost.id }))
    }

    /** What is still to leave this month from [today], counted as spending (card bills and income are not). */
    fun leftThisMonth(costs: List<FixedCost>, entries: List<MoneyEntry>, today: LocalDate): Long {
        val end = today.withDayOfMonth(today.lengthOfMonth())
        return costs.filter { it.kind == MoneyKind.EXPENSE }.sumOf { c ->
            c.occurrences(today, end).count { !recorded(c, it, entries) } * c.amount
        }
    }

    /** The ledger line for paying [cost] on [date]. */
    fun entry(cost: FixedCost, date: LocalDate) = MoneyEntry(
        date = date, kind = cost.kind, amount = cost.amount, category = cost.category,
        source = MoneySource.FIXED, merchant = cost.name, accountId = cost.accountId, fixedId = cost.id.takeIf { it > 0 },
    )

    /** [LEDG-08] The line for [u]: a card's bill moves money from its account to the card. */
    fun entry(u: Upcoming, date: LocalDate) = entry(u.cost, date).copy(toAccountId = u.cardId)

    const val CARD_BILL = "카드값"
}

/** [LEDG-09] Repeating lines that write themselves down. */
object Recurring {
    /** Days back a phone left off still catches up on; older ones are left for the user. */
    const val CATCH_UP_DAYS = 62L

    /**
     * Lines for every [FixedCost.auto] payment due up to [today] and not written yet, oldest
     * first. A line is never written before the day it was set up ([FixedCost.start]).
     */
    fun due(costs: List<FixedCost>, entries: List<MoneyEntry>, today: LocalDate): List<MoneyEntry> =
        costs.filter { it.auto && it.amount > 0 }.flatMap { c ->
            val from = maxOf(c.start ?: today, today.minusDays(CATCH_UP_DAYS))
            c.occurrences(from, today).filter { !UpcomingPayments.recorded(c, it, entries) }.map { UpcomingPayments.entry(c, it) }
        }.sortedWith(compareBy({ it.date }, { it.fixedId }))
}

/**
 * [WORK-02][LEDG-05] Every money notification the app may post: a job about to start, money still
 * owed after a job ends (받을 돈), and fixed costs three days ahead and on the day. One alarm is set
 * for the earliest time; everything due at that minute is posted together.
 */
object MoneyAlerts {
    data class Alert(val at: LocalDateTime, val key: String, val title: String, val text: String)

    val NUDGE_AT: LocalTime = LocalTime.of(10, 0)
    val BILL_AT: LocalTime = LocalTime.of(9, 0)

    fun all(
        jobs: List<Job>, costs: List<FixedCost>, entries: List<MoneyEntry>, today: LocalDate,
        receivables: List<Receivable> = emptyList(),
        accounts: List<MoneyAccount> = emptyList(),
        /** [SHOP-04] The kinds of work the user's calendars have, for the tax deadlines that go with them. */
        styles: Set<com.bamnat.core.work.WorkStyle> = emptySet(),
    ): List<Alert> = buildList {
        for (j in jobs) {
            // [FLD-03] A cancelled job neither starts nor owes.
            if (j.cancelled) continue
            val start = j.start
            val before = j.remindMinutes
            if (start != null && before != null) {
                val text = listOfNotNull(start.toString(), j.place.takeIf { it.isNotBlank() }, j.client.takeIf { it.isNotBlank() }).joinToString(" · ")
                add(Alert(j.date.atTime(start).minusMinutes(before.toLong()), "job-${j.id}", j.title.ifBlank { "일감" }, "${JobReminders.label(before)} · $text"))
            }
            if (j.unpaid > 0) {
                // [FLD-09] The day after the promised day and a week after it; without one, after the last day.
                val from = j.due ?: j.lastDate
                for ((n, after) in listOf(1L, 7L).withIndex()) {
                    add(Alert(from.plusDays(after).atTime(NUDGE_AT), "unpaid-${j.id}-$n", if (j.due != null) "입금 약속일이 지났어요" else "받을 돈이 남았어요",
                        "${j.title.ifBlank { "일감" }}${j.client.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""} · ${won(j.unpaid)}"))
                }
            }
        }
        val until = YearMonth.from(today).plusMonths(1).atEndOfMonth()
        for (c in costs.filter { it.remind }) for (due in c.occurrences(today, until)) {
            // [LEDG-09] A line that writes itself says so on the day; the rest are reminders until written.
            if (c.auto) {
                add(Alert(due.atTime(BILL_AT), "bill-${c.id}-$due-0", "가계부에 적었어요", "${c.schedule} ${c.name} ${won(c.amount)}"))
                continue
            }
            if (UpcomingPayments.recorded(c, due, entries)) continue
            if (c.kind == MoneyKind.INCOME) {
                add(Alert(due.atTime(BILL_AT), "bill-${c.id}-$due-0", "오늘 들어올 돈", "${c.name} ${won(c.amount)}"))
                continue
            }
            // A weekly line three days ahead would ring nearly every other day.
            if (c.repeat != Repeat.WEEK) {
                add(Alert(due.minusDays(3).atTime(BILL_AT), "bill-${c.id}-$due-3", "3일 뒤 나갈 돈", "${c.name} ${won(c.amount)} · ${due.monthValue}/${due.dayOfMonth}"))
            }
            add(Alert(due.atTime(BILL_AT), "bill-${c.id}-$due-0", "오늘 나갈 돈", "${c.name} ${won(c.amount)}"))
        }
        // [LEDG-08] A credit card's bill, worked out from what was spent on it.
        for (u in UpcomingPayments.within(emptyList(), entries, today, (until.toEpochDay() - today.toEpochDay()).toInt(), accounts)) {
            if (!u.cost.remind) continue
            val due = u.date
            val name = "${u.cost.name} ${UpcomingPayments.CARD_BILL}"
            add(Alert(due.minusDays(3).atTime(BILL_AT), "card-${u.cardId}-$due-3", "3일 뒤 나갈 돈", "$name ${won(u.cost.amount)} · ${due.monthValue}/${due.dayOfMonth}"))
            add(Alert(due.atTime(BILL_AT), "card-${u.cardId}-$due-0", "오늘 나갈 돈", "$name ${won(u.cost.amount)}"))
        }
        // [RECV-03] Money owed to the user: the promised day and a week after it.
        for (r in receivables) for ((n, at) in ReceivableAlerts.of(r).withIndex()) {
            val who = r.who.trim().takeIf { it.isNotBlank() && it != r.name }?.let { " ($it)" } ?: ""
            add(Alert(at.first.atTime(NUDGE_AT), "owed-${r.id}-$n", at.second, "${r.name}$who · ${won(r.left)}"))
        }
        // [SHOP-04] 부가세 and 종합소득세 deadlines for shop and field work.
        for (t in TaxDeadlines.alerts(today, styles)) add(Alert(t.at, t.key, t.title, t.text))
    }

    /** The alerts at the earliest time strictly after [now]; empty when none is ahead. */
    fun next(
        jobs: List<Job>, costs: List<FixedCost>, entries: List<MoneyEntry>, now: LocalDateTime,
        receivables: List<Receivable> = emptyList(),
        accounts: List<MoneyAccount> = emptyList(),
        styles: Set<com.bamnat.core.work.WorkStyle> = emptySet(),
    ): List<Alert> {
        val ahead = all(jobs, costs, entries, now.toLocalDate(), receivables, accounts, styles).filter { it.at.isAfter(now) }
        val first = ahead.minOfOrNull { it.at } ?: return emptyList()
        return ahead.filter { it.at == first }.sortedBy { it.key }
    }

    /** The alerts set for exactly [at], read again when the alarm fires so edits since are respected. */
    fun dueAt(
        jobs: List<Job>, costs: List<FixedCost>, entries: List<MoneyEntry>, at: LocalDateTime,
        receivables: List<Receivable> = emptyList(),
        accounts: List<MoneyAccount> = emptyList(),
        styles: Set<com.bamnat.core.work.WorkStyle> = emptySet(),
    ): List<Alert> =
        all(jobs, costs, entries, at.toLocalDate(), receivables, accounts, styles).filter { it.at == at }.sortedBy { it.key }

    private fun won(v: Long) = "%,d원".format(v)
}

/** [WORK-05] Amounts written in a day's memo (+50,000, 수입 5만원, 지출 3만 5천원) offered as ledger lines. */
object MemoAmounts {
    data class Found(val kind: MoneyKind, val amount: Long, val line: String)

    private val INCOME = Regex("수입|입금|받음|받았|벌었|팁|용돈|부수입|들어옴|들어왔")
    private val EXPENSE = Regex("지출|씀|썼|냄|냈|결제|사용|나감|나갔")
    private val AMOUNT = Regex("(\\d+(?:\\.\\d+)?)\\s*만\\s*(?:(\\d)\\s*천)?\\s*원?|(\\d)\\s*천\\s*원|(\\d{1,3}(?:,\\d{3})+|\\d{3,})\\s*원?")

    fun find(memo: String): List<Found> = memo.lines().mapNotNull { raw ->
        val line = raw.trim()
        val all = AMOUNT.findAll(line).toList()
        // A unit (원·만·천), a sign or a thousands comma marks money; a bare 2026 is a year, not won.
        val m = all.firstOrNull { Regex("[원만천]").containsMatchIn(it.value) && !line.startsWith("년", it.range.last + 1) }
            ?: all.firstOrNull { line.substring(0, it.range.first).trimEnd().let { b -> b.endsWith("+") || b.endsWith("-") || b.endsWith("＋") || b.endsWith("－") } }
            ?: all.firstOrNull { it.value.contains(',') }
            ?: all.firstOrNull { it.value.trim().length >= 4 && !line.startsWith("년", it.range.last + 1) }
            ?: return@mapNotNull null
        val amount = when {
            m.groupValues[1].isNotEmpty() -> Math.round(m.groupValues[1].toDouble() * 10_000) + (m.groupValues[2].toLongOrNull() ?: 0) * 1000
            m.groupValues[3].isNotEmpty() -> m.groupValues[3].toLong() * 1000
            else -> m.groupValues[4].replace(",", "").toLong()
        }
        if (amount <= 0) return@mapNotNull null
        val before = line.substring(0, m.range.first).trimEnd()
        val kind = when {
            before.endsWith("+") || before.endsWith("＋") -> MoneyKind.INCOME
            before.endsWith("-") || before.endsWith("－") -> MoneyKind.EXPENSE
            INCOME.containsMatchIn(line) -> MoneyKind.INCOME
            EXPENSE.containsMatchIn(line) -> MoneyKind.EXPENSE
            else -> return@mapNotNull null
        }
        Found(kind, amount, line.take(30))
    }

    /** Memo amounts not yet written down for that day (an entry from the memo with the same amount). */
    fun pending(memo: String, dayEntries: List<MoneyEntry>): List<Found> = find(memo).filter { f ->
        dayEntries.none { it.source == MoneySource.MEMO && it.amount == f.amount && it.kind == f.kind }
    }
}

/** Where the money went: the place read from a text, or what the user typed for it. */
private val MoneyEntry.place: String get() = merchant.ifBlank { note }.trim()

/** [LEDG-04] Places the user spends at most often, and amounts typed again and again. */
object MoneyStats {
    data class Place(val merchant: String, val count: Int, val amount: Long, val category: String)

    fun places(entries: List<MoneyEntry>, month: YearMonth, limit: Int = 5): List<Place> =
        entries.filter { it.kind == MoneyKind.EXPENSE && it.amount > 0 && it.place.isNotBlank() && YearMonth.from(it.date) == month }
            .groupBy { it.place }
            .map { (m, list) -> Place(m, list.size, list.sumOf { it.amount }, list.groupingBy { it.category }.eachCount().maxBy { it.value }.key) }
            .sortedWith(compareByDescending<Place> { it.count }.thenByDescending { it.amount }.thenBy { it.merchant })
            .take(limit)

    /** Amounts used at least twice in the last 90 days for [kind], most used first. */
    fun frequentAmounts(entries: List<MoneyEntry>, kind: MoneyKind, today: LocalDate, limit: Int = 4): List<Long> =
        entries.filter { it.kind == kind && it.amount > 0 && !it.date.isBefore(today.minusDays(90)) }
            .groupingBy { it.amount }.eachCount()
            .filter { it.value >= 2 }
            .entries.sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key }
}
