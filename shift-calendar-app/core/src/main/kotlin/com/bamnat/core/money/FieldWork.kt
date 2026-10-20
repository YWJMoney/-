package com.bamnat.core.money

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

/**
 * [FLD-01] How a field calendar counts its work: by the day (일당·공수, 건설·인테리어 기공) or by
 * the job (건별, 강사·촬영·1인 사장). The day sheet offers 공수 buttons for the first and 일감 적기
 * for the second (근무유형별 시장조사 4-1).
 */
enum class FieldMode(val label: String, val hint: String) {
    DAY_RATE("일당(공수)형", "오늘 1공·반공처럼 공수로 세고, 업체마다 일당이 있어요"),
    PER_JOB("건별형", "일감마다 금액·선금·잔금을 적어요"),
}

/**
 * [FLD-01][FLD-02][FLD-06] A field calendar's own settings: its mode (null until asked, which
 * works as [FieldMode.PER_JOB]), the day rate and the last 업체 the 공수 buttons fill in, and the
 * deposits the user said were not for a job.
 */
data class FieldRules(
    val mode: FieldMode? = null,
    val dayRate: Long = 0,
    val lastClient: String = "",
    /** [FLD-06] Ledger lines answered 아니에요, so they are not asked about again. */
    val notMatched: List<Long> = emptyList(),
) {
    val dayRateMode: Boolean get() = mode == FieldMode.DAY_RATE

    companion object {
        /** Lines remembered as 아니에요; older ones drop off. */
        const val KEEP_NOT_MATCHED = 50
    }
}

/** [FLD-02] 공수: one tap writes the day as a job with its units and 일당 × units. */
object DayRate {
    /** The buttons on the day sheet; 직접 asks for any other number. */
    val UNITS = listOf(1.0, 0.5, 1.5)

    /** A title for the job a tap writes, e.g. "1공", "0.5공". */
    fun label(units: Double): String = trim(units) + "공"

    /** What the month cell shows: "1공", "0.5", "1.5". A whole day says 공; a part day only its number. */
    fun cell(units: Double): String = if (units == 1.0) "1공" else trim(units)

    fun trim(units: Double): String = if (units == units.toLong().toDouble()) units.toLong().toString() else "%.2f".format(units).trimEnd('0').trimEnd('.')

    /** The amount for [units] days at [rate], to the won. */
    fun amount(rate: Long, units: Double): Long = (rate * units).roundToLong().coerceAtLeast(0)

    /**
     * The job [units] makes on [date]: [existing] (a day-rate job already there) with the new units and
     * amount, keeping what was paid; or a new one for [client] at [rate].
     */
    fun job(date: LocalDate, units: Double, rate: Long, client: String, existing: Job? = null): Job {
        val amount = amount(rate.takeIf { it > 0 } ?: existing?.let { j -> j.units?.takeIf { it > 0 }?.let { (j.amount / it).roundToLong() } } ?: 0, units)
        val base = existing ?: Job(date = date, title = label(units), client = client.trim())
        return base.copy(
            title = if (existing == null || existing.title == existing.units?.let(::label)) label(units) else existing.title,
            units = units, amount = amount, status = if (base.status == JobStatus.TENTATIVE) JobStatus.CONFIRMED else base.status,
        )
    }

    /** The day-rate job already on [date], to be changed by a tap instead of adding another. */
    fun on(date: LocalDate, jobs: List<Job>): Job? = jobs.firstOrNull { it.date == date && it.units != null && !it.cancelled }

    data class Month(val units: Double, val amount: Long, val days: Int)

    /** [FLD-02] The month's 공수 and what they come to; cancelled jobs are left out. */
    fun month(month: YearMonth, jobs: List<Job>): Month {
        val list = jobs.filter { YearMonth.from(it.date) == month && it.units != null && !it.cancelled }
        return Month(list.sumOf { it.units ?: 0.0 }, list.sumOf { it.amount }, list.map { it.date }.distinct().size)
    }

    /** [FLD-02] 업체 names from past jobs, most used and most recent first, for the client field. */
    fun clients(jobs: List<Job>, limit: Int = 8): List<String> =
        jobs.filter { it.client.isNotBlank() }
            .groupBy { it.client.trim() }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<Job>>> { it.value.size }.thenByDescending { e -> e.value.maxOf { it.date } })
            .take(limit).map { it.key }
}

/** [FLD-04] A client's colour on the calendar, the same every time for the same name. */
object ClientColors {
    /** An index into a palette of [size] colours; a blank client gets the first. */
    fun index(client: String, size: Int): Int {
        val name = client.trim()
        if (name.isEmpty() || size <= 0) return 0
        var h = 0
        for (c in name) h = h * 31 + c.code
        return Math.floorMod(h, size)
    }
}

/** [FLD-07][FLD-10] What a field worker needs once a year (5월 종합소득세) and to count days worked. */
object FieldYear {
    data class Totals(val earned: Long, val withheld: Long, val expenses: Long, val jobs: Int)

    /** This year's jobs (after 3.3%), the 3.3% held back on them, and the year's spending and job costs. */
    fun totals(year: Int, jobs: List<Job>, entries: List<MoneyEntry>): Totals {
        val j = jobs.filter { it.date.year == year }
        val spent = entries.filter { it.date.year == year && it.kind == MoneyKind.EXPENSE }.sumOf { it.amount }
        return Totals(
            earned = j.sumOf { it.earned },
            withheld = j.filter { !it.cancelled }.sumOf { it.withheld },
            expenses = spent + j.sumOf { it.costs },
            jobs = j.count { !it.cancelled },
        )
    }

    /** [FLD-10] Days with work (출역일) from [from] to [to]: each day a job that was not cancelled runs on, once. */
    fun workedDays(jobs: List<Job>, from: LocalDate, to: LocalDate): Int =
        jobs.filter { !it.cancelled && !it.lastDate.isBefore(from) && !it.date.isAfter(to) }
            .flatMap { j -> generateSequence(maxOf(j.date, from)) { it.plusDays(1) }.takeWhile { !it.isAfter(minOf(j.lastDate, to)) }.toList() }
            .toSet().size
}

/** [FLD-05] 거래내역서: one client's jobs in a month, to send when asking to be paid. */
object JobStatement {
    data class Line(val date: LocalDate, val lastDate: LocalDate, val title: String, val amount: Long, val withheld: Long, val received: Long, val unpaid: Long)

    data class Statement(val client: String, val month: YearMonth, val lines: List<Line>) {
        val amount: Long get() = lines.sumOf { it.amount }
        val withheld: Long get() = lines.sumOf { it.withheld }
        val payable: Long get() = amount - withheld
        val received: Long get() = lines.sumOf { it.received }
        val unpaid: Long get() = lines.sumOf { it.unpaid }
    }

    /** Jobs of [client] starting in [month], oldest first; cancelled ones are left out. Blank names match "거래처 미정". */
    fun of(client: String, month: YearMonth, jobs: List<Job>): Statement {
        val name = client.trim()
        val list = jobs.filter { YearMonth.from(it.date) == month && !it.cancelled && it.client.trim().ifEmpty { NO_CLIENT } == name.ifEmpty { NO_CLIENT } }
            .sortedWith(compareBy({ it.date }, { it.id }))
            .map { Line(it.date, it.lastDate, it.title + (it.units?.let { u -> " (${DayRate.label(u)})" } ?: ""), it.amount, it.withheld, it.received, it.unpaid) }
        return Statement(name.ifEmpty { NO_CLIENT }, month, list)
    }

    const val NO_CLIENT = "거래처 미정"
}

/**
 * [FLD-06] A deposit from a payment notification that may be a job's money: the same amount as a
 * job's unpaid deposit or balance (or all of it), or what is left on a receivable. Only a clear
 * match is offered, and the user says 맞아요 or 아니에요.
 */
object DepositMatch {
    enum class Part { DEPOSIT, BALANCE, ALL }

    sealed interface Target {
        val name: String
        data class ForJob(val job: Job, val part: Part) : Target {
            override val name: String get() = job.title.ifBlank { "일감" }
        }
        data class ForReceivable(val r: Receivable) : Target {
            override val name: String get() = r.name
        }
    }

    data class Match(val entry: MoneyEntry, val target: Target)

    /** Deposits this many days old are still asked about. */
    const val DAYS = 14L

    /** The job part or receivable [amount] pays, oldest first; null when nothing matches exactly. */
    fun target(amount: Long, jobs: List<Job>, receivables: List<Receivable>): Target? {
        if (amount <= 0) return null
        val fromJobs = jobs.filter { it.unpaid > 0 }.sortedWith(compareBy({ it.date }, { it.id })).firstNotNullOfOrNull { j ->
            val depositOpen = j.deposit > 0 && !j.depositPaid
            when {
                depositOpen && amount == j.deposit.coerceAtMost(j.payable) -> Target.ForJob(j, Part.DEPOSIT)
                !j.balancePaid && j.balance > 0 && amount == j.balance && (!depositOpen || j.balance != j.unpaid) -> Target.ForJob(j, Part.BALANCE)
                amount == j.unpaid -> Target.ForJob(j, Part.ALL)
                else -> null
            }
        }
        return fromJobs ?: receivables.filter { it.left == amount }.minByOrNull { it.date }?.let { Target.ForReceivable(it) }
    }

    /**
     * Notification deposits of the last [DAYS] days up to [today] that match something owed, newest
     * first, leaving out the ones answered 아니에요 ([notMatched]). Each job or receivable is offered once.
     */
    fun pending(entries: List<MoneyEntry>, jobs: List<Job>, receivables: List<Receivable>, notMatched: Collection<Long>, today: LocalDate): List<Match> {
        val used = HashSet<String>()
        return entries.filter {
            it.kind == MoneyKind.INCOME && it.source == MoneySource.NOTIFICATION && it.amount > 0 && it.id !in notMatched &&
                !it.date.isAfter(today) && !it.date.isBefore(today.minusDays(DAYS))
        }.sortedWith(compareByDescending<MoneyEntry> { it.date }.thenByDescending { it.id }).mapNotNull { e ->
            val t = target(e.amount, jobs, receivables) ?: return@mapNotNull null
            val key = when (t) { is Target.ForJob -> "job-${t.job.id}"; is Target.ForReceivable -> "r-${t.r.id}" }
            if (!used.add(key)) null else Match(e, t)
        }
    }

    /** The job with the matched part ticked as paid. */
    fun paid(job: Job, part: Part): Job = when (part) {
        Part.DEPOSIT -> job.copy(depositPaid = true)
        Part.BALANCE -> job.copy(balancePaid = true)
        Part.ALL -> JobPayments.all(job)
    }
}
