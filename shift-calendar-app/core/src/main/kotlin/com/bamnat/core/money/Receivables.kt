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

    /**
     * [RECV-05] The 받을 돈 list as the user narrowed it: a [filter] chip, a search over who, what
     * and the note, and an order. Done ones never mix in; they have their own tab.
     */
    fun narrow(owed: List<Owed>, today: LocalDate, filter: OwedFilter = OwedFilter.ALL, query: String = "", sort: OwedSort = OwedSort.URGENT): List<Owed> {
        val q = query.trim()
        val picked = owed.filter { filter.keeps(it, today) && (q.isEmpty() || it.matches(q)) }
        return when (sort) {
            OwedSort.URGENT -> picked
            OwedSort.AMOUNT -> picked.sortedWith(compareByDescending<Owed> { it.left }.thenBy { it.since })
            OwedSort.OLDEST -> picked.sortedWith(compareBy<Owed> { it.due ?: it.since }.thenByDescending { it.left })
        }
    }

    /** How many lines each chip would show, so a chip with nothing behind it can say 0. */
    fun counts(owed: List<Owed>, today: LocalDate): Map<OwedFilter, Int> = OwedFilter.entries.associateWith { f -> owed.count { f.keeps(it, today) } }

    /** Money still out from one person, so the list can show their lines together. */
    fun of(owed: List<Owed>, who: String): List<Owed> = owed.filter { it.who.trim().ifBlank { NO_NAME } == who }

    /**
     * [RECV-05] A polite note the user can send to whoever owes: what for, how much is left, and
     * the promised day when there is one. Plain words so it reads well in any messenger.
     */
    fun reminder(o: Owed, today: LocalDate): String {
        val who = o.who.trim()
        val hello = if (who.isEmpty()) "안녕하세요." else "$who 님, 안녕하세요."
        val what = when (o) {
            is Owed.FromJob -> "${o.job.date.monthValue}월 ${o.job.date.dayOfMonth}일 ${o.name}"
            is Owed.Written -> o.name.takeIf { it != who } ?: "${o.since.monthValue}월 ${o.since.dayOfMonth}일 건"
        }
        val due = o.due
        val left = won(o.left)
        val ask = when {
            due != null && today.isAfter(due) -> "${due.monthValue}월 ${due.dayOfMonth}일에 주시기로 한 ${left}이 아직 들어오지 않아서 연락드려요."
            due != null -> "${due.monthValue}월 ${due.dayOfMonth}일까지 ${left} 부탁드려요."
            else -> "아직 남은 ${left} 확인 부탁드려요."
        }
        return "$hello\n$what 관련해서 $ask\n이미 보내셨다면 이 메시지는 넘겨 주세요. 감사합니다."
    }

    private fun won(amount: Long): String = String.format(java.util.Locale.KOREA, "%,d원", amount)

    private fun Owed.matches(q: String): Boolean {
        val note = (this as? Owed.Written)?.r?.note.orEmpty() + ((this as? Owed.FromJob)?.job?.let { it.place + " " + it.note }.orEmpty())
        return listOf(who, name, note).any { it.contains(q, ignoreCase = true) }
    }
}

/** [RECV-05] The chips above the 받을 돈 list. */
enum class OwedFilter(val label: String) {
    ALL("전체"), OVERDUE("약속일 지남"), DUE_SOON("곧 받을 날"), PARTLY("일부 받음"), JOBS("일감 잔금"), WRITTEN("직접 적은 것");

    fun keeps(o: Owed, today: LocalDate): Boolean = when (this) {
        ALL -> true
        OVERDUE -> o.status(today) == ReceivableStatus.OVERDUE
        DUE_SOON -> o.status(today) == ReceivableStatus.DUE_SOON
        PARTLY -> when (o) { is Owed.Written -> o.r.received > 0; is Owed.FromJob -> o.job.received > 0 }
        JOBS -> o is Owed.FromJob
        WRITTEN -> o is Owed.Written
    }
}

/** [RECV-05] Orders for the 받을 돈 list; the first is the one the app picks. */
enum class OwedSort(val label: String) { URGENT("급한 순"), AMOUNT("금액 큰 순"), OLDEST("오래된 순") }

/** [RECV-05] What a job's 돈 받음 switches become when its money came in, without opening the job. */
object JobPayments {
    /** Every part paid: the deposit when there is one, and the rest. */
    fun all(job: Job): Job = job.copy(depositPaid = job.deposit > 0, balancePaid = true)

    /** Only the deposit (선금) paid; null when the job has no unpaid deposit. */
    fun deposit(job: Job): Job? = if (job.deposit > 0 && !job.depositPaid) job.copy(depositPaid = true) else null
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
