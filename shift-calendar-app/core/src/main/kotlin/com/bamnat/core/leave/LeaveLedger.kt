package com.bamnat.core.leave

import java.time.LocalDate

/*
 * [LEAVE-01] 연차 원장. One list of everything that changes the leave a worker has: what arises (the law's
 * lots from [LeaveAccrual], what the company actually gave, 이월, 조정, 보상휴가), what is used, paid out
 * (정산) and what lapses (소멸, 근로기준법 제60조 제7항).
 *
 * The calendar is where leave is used: a use is never stored twice, so taking a 연차 off the calendar takes it
 * off the ledger too. The stored rows ([LedgerEntry]) hold only what the calendar cannot: a grant that replaces
 * the app's own count, 이월, 조정 and 정산.
 */

/** [LEAVE-01] What a stored row of the ledger is. */
enum class EntryKind(val label: String) {
    GRANT("받은 연차"),
    CARRY("이월"),
    ADJUST("조정"),
    PAYOUT("정산"),
    ;

    companion object {
        fun of(name: String): EntryKind? = entries.firstOrNull { it.name == name }
    }
}

/** [LEAVE-01] One stored row of the ledger (the `leave_ledger` table). */
data class LedgerEntry(
    val id: Long = 0,
    val kind: EntryKind,
    /** When it happens: a grant or 이월 arises on this date, 정산 is paid on it. */
    val date: LocalDate,
    /** Days, always positive except an [EntryKind.ADJUST], which may take days away. */
    val days: Double,
    /** For a grant, 이월 and 조정: the last day it can be used; null for no end. */
    val expires: LocalDate? = null,
    /** For a grant: the leave year it stands for, so the app's own lot of that year steps aside. */
    val yearStart: LocalDate? = null,
    val note: String = "",
    val pool: LeavePool = LeavePool.ANNUAL,
)

/** One day of leave taken on the calendar. */
data class LeaveUse(val date: LocalDate, val days: Double, val pool: LeavePool = LeavePool.ANNUAL)

/** [LEAVE-01] What one line of the ledger says. */
enum class LineKind(val label: String) {
    ARISE("생김"), USE("씀"), PLANNED("쓸 예정"), PAYOUT("정산"), LAPSE("소멸"),
}

data class LedgerLine(
    val date: LocalDate,
    val kind: LineKind,
    /** Plus for leave that arises, minus for leave used, paid out or lapsed. */
    val days: Double,
    val title: String,
    val note: String = "",
    val pool: LeavePool = LeavePool.ANNUAL,
    /** The stored row behind the line, for editing; null when the app worked it out. */
    val entryId: Long? = null,
    /** For a line that arises: until when. */
    val expires: LocalDate? = null,
    /** Annual leave left after this line, counting every line before it. */
    val balance: Double = 0.0,
)

/**
 * [LEAVE-01] A lot on the ledger's day: [left] usable today, [used] by the end of the ledger (planned days
 * too) and [lapsed], what lapses unused by then.
 */
data class LotState(val lot: LeaveLot, val used: Double, val left: Double, val lapsed: Double)

/** [LEAVE-01] The ledger worked out on [today]. */
data class LeaveSummary(
    val today: LocalDate,
    val lines: List<LedgerLine>,
    val lots: List<LotState>,
    /** Annual leave usable today. Negative when more was used than had arisen (미리 쓴 연차). */
    val available: Double,
    /** Annual leave on the calendar after today. */
    val planned: Double,
    /** 보상휴가 usable today. */
    val comp: Double,
    /** Annual leave that arose up to today, and used up to today. */
    val arisen: Double,
    val used: Double,
) {
    /** What is left once the planned days are taken. */
    val afterPlanned: Double get() = available - planned

    /** The next date annual leave lapses after today, with what would lapse then after the planned days. */
    val nextLapse: Pair<LocalDate, Double>?
        get() = lines.filter { it.kind == LineKind.LAPSE && it.date.isAfter(today) && it.pool == LeavePool.ANNUAL }
            .groupBy { it.date }.minByOrNull { it.key }?.let { (d, l) -> d to -l.sumOf { it.days } }

    /** Lots in use today (arisen, not lapsed), soonest to lapse first. */
    val open: List<LotState> get() = lots.filter { !it.lot.on.isAfter(today) && (it.lot.expires == null || !it.lot.expires.isBefore(today)) }
        .sortedWith(compareBy(nullsLast()) { it.lot.expires })
}

object LeaveLedger {
    private const val EPS = 1e-9

    /**
     * [LEAVE-01] The ledger: [auto] lots (the law's, from [LeaveAccrual.lots]; a stored grant for the same
     * leave year takes the place of one), the stored [entries], [compLots] from the calendar and the calendar's
     * [uses]. Uses take from the lot that lapses first among those that have arisen; with none left the day
     * is 미리 쓴 연차 and the next lot to arise pays it back first. Lines run through [until].
     */
    fun build(
        today: LocalDate,
        auto: List<LeaveLot>,
        entries: List<LedgerEntry>,
        uses: List<LeaveUse>,
        compLots: List<LeaveLot> = emptyList(),
        until: LocalDate = today.plusYears(1),
    ): LeaveSummary {
        val grantYears = entries.filter { it.kind == EntryKind.GRANT && it.yearStart != null }.map { it.yearStart }.toSet()
        val stored = entries.filter { it.kind != EntryKind.PAYOUT }.map { e ->
            LeaveLot(
                kind = when (e.kind) { EntryKind.GRANT -> LotKind.GRANT; EntryKind.CARRY -> LotKind.CARRY; else -> LotKind.ADJUST },
                days = e.days, on = e.date, expires = e.expires, yearStart = e.yearStart, note = e.note, id = e.id,
            ).let { if (e.pool == LeavePool.COMP && it.kind == LotKind.ADJUST) it.copy(kind = LotKind.COMP) else it }
        }
        val lots = (auto.filter { it.yearStart == null || it.kind == LotKind.MONTHLY || it.yearStart !in grantYears } + stored + compLots)
            .filter { !it.on.isAfter(until) }
        // Events in date order: what arises first, then uses and 정산, then what lapses at the day's end.
        data class Event(val date: LocalDate, val order: Int, val lot: LeaveLot? = null, val use: LeaveUse? = null, val payout: LedgerEntry? = null)
        val events = buildList {
            lots.forEach { add(Event(it.on, 0, lot = it)) }
            uses.filter { !it.date.isAfter(until) }.forEach { add(Event(it.date, 1, use = it)) }
            entries.filter { it.kind == EntryKind.PAYOUT && !it.date.isAfter(until) }.forEach { add(Event(it.date, 1, payout = it)) }
            // A lot lapses after its last day.
            lots.forEach { l -> l.expires?.takeIf { !it.isAfter(until) }?.let { add(Event(it, 2, lot = l)) } }
        }.sortedWith(compareBy({ it.date }, { it.order }))

        val left = HashMap<LeaveLot, Double>()
        val used = HashMap<LeaveLot, Double>()
        val lapsed = HashMap<LeaveLot, Double>()
        val debt = doubleArrayOf(0.0, 0.0) // by pool: used before anything arose for it
        val lines = mutableListOf<LedgerLine>()
        var arisenToday = 0.0
        var usedToday = 0.0
        var snapshot: Map<LeaveLot, Double>? = null
        var debtToday = 0.0
        var compToday = 0.0
        fun annualBalance() = left.filterKeys { it.pool == LeavePool.ANNUAL }.values.sum() - debt[0]
        fun take(pool: LeavePool, date: LocalDate, days: Double): Double {
            var need = days
            val open = left.keys.filter { it.pool == pool && !it.on.isAfter(date) && (it.expires == null || !it.expires.isBefore(date)) && left.getValue(it) > EPS }
                .sortedWith(compareBy<LeaveLot, LocalDate?>(nullsLast()) { it.expires }.thenBy { it.on })
            for (l in open) {
                if (need <= EPS) break
                val t = minOf(need, left.getValue(l))
                left[l] = left.getValue(l) - t
                used[l] = (used[l] ?: 0.0) + t
                need -= t
            }
            if (need > EPS) debt[pool.ordinal] += need
            return need
        }

        for (ev in events) {
            if (snapshot == null && (ev.date.isAfter(today) || ev.date == today && ev.order == 2)) {
                snapshot = HashMap(left); debtToday = debt[0]
                compToday = left.filterKeys { it.pool == LeavePool.COMP }.values.sum() - debt[1]
            }
            val lot = ev.lot
            when {
                lot != null && ev.order == 0 -> {
                    var days = lot.days
                    // An adjustment that takes days away is used like leave.
                    if (days < 0) {
                        take(lot.pool, lot.on, -days)
                        lines += LedgerLine(lot.on, LineKind.ARISE, days, lot.kind.label, lot.note, lot.pool, lot.id, balance = annualBalance())
                        continue
                    }
                    // 미리 쓴 연차 comes off what arises next.
                    val owed = minOf(debt[lot.pool.ordinal], days)
                    debt[lot.pool.ordinal] -= owed
                    left[lot] = days - owed
                    if (owed > 0) used[lot] = owed
                    if (lot.pool == LeavePool.ANNUAL && !lot.on.isAfter(today)) arisenToday += days
                    lines += LedgerLine(lot.on, LineKind.ARISE, days, lot.kind.label,
                        listOfNotNull(lot.note.takeIf { it.isNotBlank() }, if (owed > 0) "미리 쓴 ${LeaveAccrual.days(owed)} 뺌" else null).joinToString(" · "),
                        lot.pool, lot.id, lot.expires, annualBalance())
                }
                lot != null -> {
                    val rest = left[lot] ?: 0.0
                    if (rest > EPS) {
                        left[lot] = 0.0
                        lapsed[lot] = rest
                        lines += LedgerLine(ev.date, LineKind.LAPSE, -rest, "${lot.kind.label} 소멸",
                            "${ev.date.monthValue}/${ev.date.dayOfMonth}까지 쓰지 않은 날", lot.pool, lot.id, balance = annualBalance())
                    }
                }
                ev.use != null -> {
                    val u = ev.use
                    val planned = u.date.isAfter(today)
                    val short = take(u.pool, u.date, u.days)
                    if (u.pool == LeavePool.ANNUAL && !planned) usedToday += u.days
                    lines += LedgerLine(u.date, if (planned) LineKind.PLANNED else LineKind.USE, -u.days,
                        if (u.pool == LeavePool.COMP) "보상휴가" else "연차",
                        if (short > EPS) "남은 날보다 ${LeaveAccrual.days(short)} 더 씀" else "", u.pool, balance = annualBalance())
                }
                ev.payout != null -> {
                    val p = ev.payout
                    take(p.pool, p.date, p.days)
                    lines += LedgerLine(p.date, LineKind.PAYOUT, -p.days, EntryKind.PAYOUT.label, p.note, p.pool, p.id, balance = annualBalance())
                }
            }
        }
        if (snapshot == null) {
            snapshot = HashMap(left); debtToday = debt[0]
            compToday = left.filterKeys { it.pool == LeavePool.COMP }.values.sum() - debt[1]
        }
        val snap: Map<LeaveLot, Double> = snapshot!!
        val available = snap.filterKeys { it.pool == LeavePool.ANNUAL && (it.expires == null || !it.expires.isBefore(today)) }.values.sum() - debtToday
        val planned = uses.filter { it.pool == LeavePool.ANNUAL && it.date.isAfter(today) && !it.date.isAfter(until) }.sumOf { it.days }
        return LeaveSummary(
            today = today,
            lines = lines,
            lots = lots.map { LotState(it, used[it] ?: 0.0, round(snap[it] ?: 0.0), lapsed[it] ?: 0.0) },
            available = round(available),
            planned = round(planned),
            comp = round(compToday),
            arisen = round(arisenToday),
            used = round(usedToday),
        )
    }

    private fun round(v: Double) = Math.round(v * 10000) / 10000.0
}
