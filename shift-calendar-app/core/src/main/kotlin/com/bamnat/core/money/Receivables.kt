package com.bamnat.core.money

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * [RECV-01] What a 미수금 is, so the app knows whether getting it back is income. A sale on credit
 * (외상) is usually already in that day's sales, and a loan coming back is not income at all.
 */
enum class ReceivableKind(val label: String, val hint: String, val incomeByDefault: Boolean) {
    SALE("외상·미수 매출", "손님이나 거래처가 아직 안 낸 물건값·서비스값", false),
    WORK("밀린 일당·급여", "일은 했는데 아직 못 받은 돈", true),
    LOAN("빌려준 돈", "돌려받아도 수입이 아니에요", false),
    OTHER("기타", "보증금, 환불 기다리는 돈 등", true),
}

/** [RECV-01] Money that came in for a receivable; [entryId] is its ledger line when it was counted as income. */
data class ReceivablePayment(
    val id: Long = 0,
    val receivableId: Long = 0,
    val date: LocalDate,
    val amount: Long,
    val entryId: Long? = null,
)

/**
 * [RECV-01] 미수금: money someone owes the user, written down once and paid back in parts. The
 * debt keeps its first day ([date]) and an optional promised day ([due]); [closedOn] marks one the
 * user stopped chasing (받지 않기로 함), which then no longer counts as owed.
 */
data class Receivable(
    val id: Long = 0,
    /** Who owes it: a customer, a company, a friend. */
    val who: String,
    /** What it is for, e.g. "10월 납품", "3일치 일당". */
    val title: String = "",
    val amount: Long,
    val date: LocalDate,
    val due: LocalDate? = null,
    val kind: ReceivableKind = ReceivableKind.SALE,
    val note: String = "",
    /** Whether money that comes back is written into the ledger as income. */
    val countAsIncome: Boolean = kind.incomeByDefault,
    val closedOn: LocalDate? = null,
    val payments: List<ReceivablePayment> = emptyList(),
) {
    val received: Long get() = payments.sumOf { it.amount }
    val left: Long get() = if (closedOn != null) 0 else (amount - received).coerceAtLeast(0)
    val paidOff: Boolean get() = closedOn == null && received >= amount
    val lastPaid: LocalDate? get() = payments.maxOfOrNull { it.date }
    val name: String get() = title.ifBlank { who.ifBlank { kind.label } }

    fun status(today: LocalDate): ReceivableStatus = when {
        closedOn != null -> ReceivableStatus.CLOSED
        paidOff -> ReceivableStatus.PAID
        due != null && today.isAfter(due) -> ReceivableStatus.OVERDUE
        due != null && !today.isBefore(due.minusDays(DUE_SOON_DAYS)) -> ReceivableStatus.DUE_SOON
        received > 0 -> ReceivableStatus.PARTLY
        else -> ReceivableStatus.OPEN
    }

    /** Days past the promised day, or since the debt began when nobody promised a day. */
    fun daysWaiting(today: LocalDate): Long = ChronoUnit.DAYS.between(due ?: date, today).coerceAtLeast(0)

    companion object {
        const val DUE_SOON_DAYS = 3L
    }
}

enum class ReceivableStatus(val label: String) {
    OVERDUE("약속일 지남"), DUE_SOON("곧 받을 날"), PARTLY("일부 받음"), OPEN("받을 돈"), PAID("다 받음"), CLOSED("받지 않기로 함"),
}

/**
 * [RECV-02] One line of the 받을 돈 list: a receivable written down here, or a field job whose
 * deposit or balance is still out ([Job.unpaid], [WORK-02]), so the user sees everything owed in
 * one place without typing a job twice.
 */
sealed interface Owed {
    val who: String
    val name: String
    val left: Long
    val since: LocalDate
    val due: LocalDate?
    fun status(today: LocalDate): ReceivableStatus

    data class Written(val r: Receivable) : Owed {
        override val who get() = r.who
        override val name get() = r.name
        override val left get() = r.left
        override val since get() = r.date
        override val due get() = r.due
        override fun status(today: LocalDate) = r.status(today)
    }

    data class FromJob(val job: Job) : Owed {
        override val who get() = job.client
        override val name get() = job.title.ifBlank { "일감" }
        override val left get() = job.unpaid
        override val since get() = job.lastDate
        override val due: LocalDate? get() = null
        override fun status(today: LocalDate) = if (job.received > 0) ReceivableStatus.PARTLY else ReceivableStatus.OPEN
    }
}

/** [RECV-02] The totals the 받을 돈 card and screen show. */
data class OwedSummary(
    val total: Long,
    val count: Int,
    val overdue: Long,
    val overdueCount: Int,
    val dueSoon: Long,
    /** Money left per person, most first; blank names are grouped as "이름 없음". */
    val byWho: List<Pair<String, Long>>,
    /** Owed for 0–30, 31–60, 61–90 and over 90 days, counted from the promised day or the first day. */
    val aging: List<Long>,
    /** Came in during the month of today. */
    val receivedThisMonth: Long,
)

object Receivables {
    val AGING_LABELS = listOf("30일 안", "31~60일", "61~90일", "90일 넘음")

    /** Everything still owed up to [today], most pressing first: overdue, due soon, then oldest. */
    fun owed(receivables: List<Receivable>, jobs: List<Job>, today: LocalDate): List<Owed> {
        val written = receivables.filter { it.left > 0 }.map { Owed.Written(it) }
        val fromJobs = MoneyCalculator.unpaid(jobs, today).map { Owed.FromJob(it) }
        val rank = listOf(ReceivableStatus.OVERDUE, ReceivableStatus.DUE_SOON, ReceivableStatus.PARTLY, ReceivableStatus.OPEN)
        return (written + fromJobs).sortedWith(
            compareBy<Owed> { rank.indexOf(it.status(today)) }.thenBy { it.due ?: it.since }.thenBy { it.since }.thenBy { it.name },
        )
    }

    fun summary(receivables: List<Receivable>, jobs: List<Job>, today: LocalDate): OwedSummary {
        val owed = owed(receivables, jobs, today)
        val overdue = owed.filter { it.status(today) == ReceivableStatus.OVERDUE }
        val aging = LongArray(4)
        for (o in owed) {
            val days = ChronoUnit.DAYS.between(o.due ?: o.since, today).coerceAtLeast(0)
            aging[when { days <= 30 -> 0; days <= 60 -> 1; days <= 90 -> 2; else -> 3 }] += o.left
        }
        val month = java.time.YearMonth.from(today)
        return OwedSummary(
            total = owed.sumOf { it.left },
            count = owed.size,
            overdue = overdue.sumOf { it.left },
            overdueCount = overdue.size,
            dueSoon = owed.filter { it.status(today) == ReceivableStatus.DUE_SOON }.sumOf { it.left },
            byWho = owed.groupBy { it.who.trim().ifBlank { NO_NAME } }
                .map { (who, list) -> who to list.sumOf { it.left } }
                .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }),
            aging = aging.toList(),
            receivedThisMonth = receivables.flatMap { it.payments }.filter { java.time.YearMonth.from(it.date) == month }.sumOf { it.amount },
        )
    }

    /**
     * The ledger line for money that came back on [date], or null when it is not income
     * ([Receivable.countAsIncome] off: a loan, or a sale already in that day's sales).
     */
    fun incomeEntry(r: Receivable, amount: Long, date: LocalDate): MoneyEntry? {
        if (!r.countAsIncome || amount <= 0) return null
        val category = if (r.kind == ReceivableKind.WORK) "부수입" else "기타"
        return MoneyEntry(
            date = date, kind = MoneyKind.INCOME, amount = amount, category = category,
            note = "받을 돈 · ${r.name}", merchant = r.who.trim(), source = MoneySource.MANUAL,
        )
    }

    /** What is left to come in, at most; a payment larger than that is cut so the debt never goes below zero. */
    fun clampPayment(r: Receivable, amount: Long): Long = amount.coerceIn(0, (r.amount - r.received).coerceAtLeast(0))

    const val NO_NAME = "이름 없음"
}

/**
 * [RECV-03] When to remind about a receivable: on the promised day, then 3 and 7 days after it
 * when it is still out. Without a promised day, once 30 days after it began. Written for
 * [MoneyAlerts], which posts them together with the job and bill alerts.
 */
object ReceivableAlerts {
    fun of(r: Receivable): List<Pair<LocalDate, String>> {
        if (r.left <= 0) return emptyList()
        val due = r.due
        return if (due != null) listOf(due to "오늘 받기로 한 날", due.plusDays(3) to "약속일이 3일 지났어요", due.plusDays(7) to "약속일이 일주일 지났어요")
        else listOf(r.date.plusDays(30) to "받을 돈이 한 달째 남았어요")
    }
}
