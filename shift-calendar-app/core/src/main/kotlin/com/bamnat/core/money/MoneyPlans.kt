package com.bamnat.core.money

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * [LEDG-05] Money that leaves on the same day every month: rent, insurance, phone, the card bill.
 * A card bill is counted as a transfer (카드값), because the card's payments are already expenses.
 */
data class FixedCost(
    val id: Long = 0,
    val name: String,
    val amount: Long,
    /** Day of the month, 1..31; a day past the month's end means its last day (말일). */
    val day: Int,
    val kind: MoneyKind = MoneyKind.EXPENSE,
    val category: String = "주거",
    val remind: Boolean = true,
) {
    fun dueIn(month: YearMonth): LocalDate = month.atDay(day.coerceIn(1, month.lengthOfMonth()))
}

data class Upcoming(val cost: FixedCost, val date: LocalDate, val daysLeft: Int)

object UpcomingPayments {
    /** Whether this month's payment of [cost] is already written down. */
    fun recorded(cost: FixedCost, month: YearMonth, entries: List<MoneyEntry>): Boolean = entries.any {
        it.source == MoneySource.FIXED && it.merchant == cost.name && YearMonth.from(it.date) == month
    }

    /** Payments due from [today] for [days] days that are not yet recorded, soonest first. */
    fun within(costs: List<FixedCost>, entries: List<MoneyEntry>, today: LocalDate, days: Int = 7): List<Upcoming> =
        costs.flatMap { c ->
            listOf(YearMonth.from(today), YearMonth.from(today).plusMonths(1)).map { m -> c to c.dueIn(m) }
        }.filter { (c, d) ->
            !d.isBefore(today) && ChronoUnit.DAYS.between(today, d) <= days && !recorded(c, YearMonth.from(d), entries)
        }.map { (c, d) -> Upcoming(c, d, ChronoUnit.DAYS.between(today, d).toInt()) }
            .sortedWith(compareBy({ it.date }, { it.cost.id }))

    /** What is still to leave this month after [today], counted as spending (card bills are not). */
    fun leftThisMonth(costs: List<FixedCost>, entries: List<MoneyEntry>, today: LocalDate): Long {
        val m = YearMonth.from(today)
        return costs.filter { it.kind == MoneyKind.EXPENSE && !it.dueIn(m).isBefore(today) && !recorded(it, m, entries) }.sumOf { it.amount }
    }

    /** The ledger line for paying [cost] on [date]. */
    fun entry(cost: FixedCost, date: LocalDate) = MoneyEntry(
        date = date, kind = cost.kind, amount = cost.amount, category = cost.category,
        source = MoneySource.FIXED, merchant = cost.name,
    )
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
    ): List<Alert> = buildList {
        for (j in jobs) {
            val start = j.start
            val before = j.remindMinutes
            if (start != null && before != null) {
                val text = listOfNotNull(start.toString(), j.place.takeIf { it.isNotBlank() }, j.client.takeIf { it.isNotBlank() }).joinToString(" · ")
                add(Alert(j.date.atTime(start).minusMinutes(before.toLong()), "job-${j.id}", j.title.ifBlank { "일감" }, "${JobReminders.label(before)} · $text"))
            }
            if (j.unpaid > 0) {
                for ((n, after) in listOf(1L, 7L).withIndex()) {
                    add(Alert(j.lastDate.plusDays(after).atTime(NUDGE_AT), "unpaid-${j.id}-$n", "받을 돈이 남았어요",
                        "${j.title.ifBlank { "일감" }}${j.client.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""} · ${won(j.unpaid)}"))
                }
            }
        }
        val months = listOf(YearMonth.from(today), YearMonth.from(today).plusMonths(1))
        for (c in costs.filter { it.remind }) for (m in months) {
            if (UpcomingPayments.recorded(c, m, entries)) continue
            val due = c.dueIn(m)
            add(Alert(due.minusDays(3).atTime(BILL_AT), "bill-${c.id}-$m-3", "3일 뒤 나갈 돈", "${c.name} ${won(c.amount)} · ${due.monthValue}/${due.dayOfMonth}"))
            add(Alert(due.atTime(BILL_AT), "bill-${c.id}-$m-0", "오늘 나갈 돈", "${c.name} ${won(c.amount)}"))
        }
        // [RECV-03] Money owed to the user: the promised day and a week after it.
        for (r in receivables) for ((n, at) in ReceivableAlerts.of(r).withIndex()) {
            val who = r.who.trim().takeIf { it.isNotBlank() && it != r.name }?.let { " ($it)" } ?: ""
            add(Alert(at.first.atTime(NUDGE_AT), "owed-${r.id}-$n", at.second, "${r.name}$who · ${won(r.left)}"))
        }
    }

    /** The alerts at the earliest time strictly after [now]; empty when none is ahead. */
    fun next(
        jobs: List<Job>, costs: List<FixedCost>, entries: List<MoneyEntry>, now: LocalDateTime,
        receivables: List<Receivable> = emptyList(),
    ): List<Alert> {
        val ahead = all(jobs, costs, entries, now.toLocalDate(), receivables).filter { it.at.isAfter(now) }
        val first = ahead.minOfOrNull { it.at } ?: return emptyList()
        return ahead.filter { it.at == first }.sortedBy { it.key }
    }

    /** The alerts set for exactly [at], read again when the alarm fires so edits since are respected. */
    fun dueAt(
        jobs: List<Job>, costs: List<FixedCost>, entries: List<MoneyEntry>, at: LocalDateTime,
        receivables: List<Receivable> = emptyList(),
    ): List<Alert> =
        all(jobs, costs, entries, at.toLocalDate(), receivables).filter { it.at == at }.sortedBy { it.key }

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
