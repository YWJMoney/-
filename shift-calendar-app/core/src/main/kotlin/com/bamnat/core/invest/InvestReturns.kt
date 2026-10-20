package com.bamnat.core.invest

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** [INV-05] A period's return by one method, with what it was worked out from. */
data class PeriodReturn(
    val method: ReturnMethod,
    /** 0.05 is 5%; null when there is nothing to divide by (no money in yet). */
    val rate: Double?,
    /** Gain over the period: end value less start value less money put in meanwhile. */
    val pnl: Long,
    val startValue: Long,
    val endValue: Long,
    /** Money put in less money taken out during the period. */
    val flows: Long,
)

/**
 * [INV-05] Returns three ways, as 업비트's 투자손익 explains them: 원금 대비 (simple), 금액가중 (Modified
 * Dietz, money weighted by the days it was in) and 시간가중 (sub-periods between the days a value is
 * known, chained so deposits do not count as gains). Values on past days come from [Snapshot]s the app
 * saves whenever prices were typed; between them, trades and deposits are counted at cost.
 */
object InvestReturns {
    /** Money put in less money taken out, up to and including [date]. */
    fun principalAt(book: InvestBook, date: LocalDate): Long = book.cash.filter { !it.date.isAfter(date) }.sumOf { it.principalWon }

    /** Cash and holdings at cost on [date]: no prices needed. */
    fun bookValueAt(book: InvestBook, date: LocalDate): Long = Portfolio.build(book, upTo = date, useQuotes = false).summary.totalAssets

    /**
     * What everything was worth on [date]. Today (or later) it is [live]; before, the last snapshot on or
     * before it, moved by what was bought, sold, put in or taken out since (at cost); without any
     * snapshot, the value at cost.
     */
    fun valueAt(book: InvestBook, date: LocalDate, today: LocalDate, live: PortfolioSummary?): Long {
        if (!date.isBefore(today) && live != null) return live.totalAssets
        val snap = book.snapshots.filter { !it.date.isAfter(date) }.maxByOrNull { it.date } ?: return bookValueAt(book, date)
        if (snap.date == date) return snap.value
        return snap.value + bookValueAt(book, date) - bookValueAt(book, snap.date)
    }

    /** [INV-05] 원금 대비: everything now less everything put in, over what was put in. */
    fun simple(summary: PortfolioSummary): PeriodReturn =
        PeriodReturn(ReturnMethod.SIMPLE, summary.simpleReturn, summary.pnl, 0, summary.totalAssets, summary.principal)

    /**
     * [INV-05] 금액가중 (Modified Dietz): gain ÷ (start value + each deposit or withdrawal weighted by the
     * share of the period it was in the account).
     */
    fun moneyWeighted(book: InvestBook, from: LocalDate, to: LocalDate, today: LocalDate, live: PortfolioSummary?): PeriodReturn {
        val end = minOf(to, maxOf(today, from))
        val v0 = valueAt(book, from.minusDays(1), today, live)
        val v1 = valueAt(book, end, today, live)
        val flows = book.cash.filter { it.kind.principalSign != 0 && !it.date.isBefore(from) && !it.date.isAfter(end) }
        val days = ChronoUnit.DAYS.between(from, end) + 1.0
        val net = flows.sumOf { it.principalWon }
        // Money in on the first day works the whole period; on the last day, almost none of it.
        val weighted = flows.sumOf { f -> f.principalWon * (days - ChronoUnit.DAYS.between(from, f.date)) / days }
        val base = v0 + weighted
        val pnl = v1 - v0 - net
        return PeriodReturn(ReturnMethod.MONEY_WEIGHTED, if (base > 0.5) pnl / base else null, pnl, v0, v1, net)
    }

    /**
     * [INV-05][INV-21] 시간가중: the period cut at each day a value is known (snapshots), each piece's
     * growth worked out with that piece's deposits counted from its start, and the pieces multiplied.
     */
    fun timeWeighted(book: InvestBook, from: LocalDate, to: LocalDate, today: LocalDate, live: PortfolioSummary?): PeriodReturn {
        val end = minOf(to, maxOf(today, from))
        val start = from.minusDays(1)
        val points = ArrayList<Triple<LocalDate, Long, Long>>()
        points += Triple(start, valueAt(book, start, today, live), principalAt(book, start))
        book.snapshots.filter { it.date.isAfter(start) && it.date.isBefore(end) }.sortedBy { it.date }
            .forEach { points += Triple(it.date, it.value, it.principal) }
        points += Triple(end, valueAt(book, end, today, live), principalAt(book, end))
        var growth = 1.0
        var any = false
        for (i in 1 until points.size) {
            val (_, va, pa) = points[i - 1]
            val (_, vb, pb) = points[i]
            val base = va + (pb - pa)
            if (base <= 0) continue
            growth *= vb.toDouble() / base
            any = true
        }
        val v0 = points.first().second
        val v1 = points.last().second
        val net = points.last().third - points.first().third
        return PeriodReturn(ReturnMethod.TIME_WEIGHTED, if (any) growth - 1 else null, v1 - v0 - net, v0, v1, net)
    }

    fun of(method: ReturnMethod, book: InvestBook, from: LocalDate, to: LocalDate, today: LocalDate, live: PortfolioSummary): PeriodReturn =
        when (method) {
            ReturnMethod.SIMPLE -> simple(live)
            ReturnMethod.MONEY_WEIGHTED -> moneyWeighted(book, from, to, today, live)
            ReturnMethod.TIME_WEIGHTED -> timeWeighted(book, from, to, today, live)
        }

    /** The first day money moved, the start of "처음부터". */
    fun firstDay(book: InvestBook): LocalDate? = (book.cash.map { it.date } + book.trades.map { it.date }).minOrNull()

    /**
     * [INV-21] Today's snapshot to keep, once every held holding has a price: otherwise the value
     * counts some holdings at cost and would make a false step in the 시간가중 history.
     */
    fun snapshotFor(today: LocalDate, summary: PortfolioSummary, hasHoldings: Boolean): Snapshot? {
        if (summary.missingQuotes.isNotEmpty() || (summary.principal == 0L && !hasHoldings)) return null
        return Snapshot(today, summary.totalAssets, summary.principal)
    }
}
