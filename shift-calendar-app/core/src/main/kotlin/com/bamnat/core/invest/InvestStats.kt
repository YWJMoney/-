package com.bamnat.core.invest

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToLong

/** [INV-06] One holding's part of a period: what selling it made, what it paid out and how often it was traded. */
data class SymbolPnl(val key: String, val name: String, val market: Market, val realized: Long, val dividends: Long, val trades: Int, val wins: Int, val sells: Int) {
    val total: Long get() = realized + dividends
}

/** [INV-13] How trades made in one state went: an emotion, or keeping (or not keeping) the rules. */
data class TagPnl(val label: String, val sells: Int, val wins: Int, val pnl: Long) {
    val winRate: Double? get() = if (sells > 0) wins.toDouble() / sells else null
}

/** [INV-06][INV-13][INV-18] A period of trading in numbers. */
data class PeriodStats(
    val from: LocalDate,
    val to: LocalDate,
    val realized: Long,
    val income: Long,
    val fees: Long,
    val taxes: Long,
    val bought: Long,
    val sold: Long,
    val deposits: Long,
    val withdrawals: Long,
    val trades: Int,
    val sells: Int,
    val wins: Int,
    val losses: Int,
    val grossWin: Long,
    val grossLoss: Long,
    val best: Realization?,
    val worst: Realization?,
    val bySymbol: List<SymbolPnl>,
    val byEmotion: List<TagPnl>,
    val byRules: List<TagPnl>,
    val byWeekday: Map<DayOfWeek, Long>,
    val winDays: Int,
    val lossDays: Int,
    val longestWinStreak: Int,
    val longestLossStreak: Int,
    val averageHoldingDays: Double?,
    val journalDays: Int,
    /** [INV-06] 기타 비용 written as cash: in [fees] and off [pnl], never off [income]. */
    val otherCosts: Long = 0,
) {
    /** What the period earned: sales and payouts (fees and tax already off both), less other costs. */
    val pnl: Long get() = realized + income - otherCosts
    /** [INV-06] 승률: sales that made money over all sales. */
    val winRate: Double? get() = if (sells > 0) wins.toDouble() / sells else null
    val averageWin: Long? get() = if (wins > 0) grossWin / wins else null
    val averageLoss: Long? get() = if (losses > 0) grossLoss / losses else null
    /** [INV-13] 손익비: the average win over the average loss. */
    val payoff: Double? get() = averageWin?.let { w -> averageLoss?.takeIf { it > 0 }?.let { w.toDouble() / it } }
    /** [INV-13] Profit factor: all wins over all losses. */
    val profitFactor: Double? get() = if (grossLoss > 0) grossWin.toDouble() / grossLoss else null
    val isEmpty: Boolean get() = trades == 0 && income == 0L && otherCosts == 0L && deposits == 0L && withdrawals == 0L
}

object InvestStats {
    fun period(book: InvestBook, result: PortfolioResult, from: LocalDate, to: LocalDate): PeriodStats {
        val inRange = { d: LocalDate -> !d.isBefore(from) && !d.isAfter(to) }
        val trades = book.trades.filter { inRange(it.date) }
        val cash = book.cash.filter { inRange(it.date) }
        val sales = result.realizations.filter { inRange(it.date) }
        val wins = sales.filter { it.win }
        val losses = sales.filter { it.loss }
        val names = result.byKey.associate { it.key to it.title }
        val dividendsBy = cash.filter { it.kind == CashKind.DIVIDEND && it.symbol.isNotBlank() }
            .groupBy { holdingKey(it.symbol, "") }.mapValues { (_, l) -> l.sumOf { it.amount - it.tax } }
        val keys = (trades.map { it.key } + dividendsBy.keys).distinct()
        val bySymbol = keys.map { k ->
            val ts = trades.filter { it.key == k }
            val ss = sales.filter { it.key == k }
            SymbolPnl(k, names[k] ?: ts.firstOrNull()?.title ?: k, ts.firstOrNull()?.market ?: Market.OTHER,
                ss.sumOf { it.pnlWon }.roundToLong(), dividendsBy[k] ?: 0L, ts.size, ss.count { it.win }, ss.size)
        }.sortedWith(compareByDescending<SymbolPnl> { abs(it.total) }.thenBy { it.name })
        val byEmotion = Emotion.entries.mapNotNull { e ->
            val ss = sales.filter { e in it.emotions }
            if (ss.isEmpty()) null else TagPnl(e.label, ss.size, ss.count { it.win }, ss.sumOf { it.pnlWon }.roundToLong())
        }.sortedByDescending { it.sells }
        val byRules = listOf(true to "원칙 지킴", false to "원칙 어김").mapNotNull { (kept, label) ->
            val ss = sales.filter { it.followedRules == kept }
            if (ss.isEmpty()) null else TagPnl(label, ss.size, ss.count { it.win }, ss.sumOf { it.pnlWon }.roundToLong())
        }
        val daily = Portfolio.dailyPnl(result, book.cash).filterKeys(inRange)
        val byWeekday = DayOfWeek.entries.associateWith { d -> daily.filterKeys { it.dayOfWeek == d }.values.sum() }
        val (winStreak, lossStreak) = streaks(daily)
        val held = sales.mapNotNull { it.holdingDays }
        return PeriodStats(
            from = from, to = to,
            realized = sales.sumOf { it.pnlWon }.roundToLong(),
            income = cash.filter { it.kind.isIncome }.sumOf { it.amount - it.tax },
            otherCosts = cash.filter { it.kind == CashKind.FEE }.sumOf { it.amount },
            fees = (trades.sumOf { it.fee * it.fxRate }).roundToLong() + cash.filter { it.kind == CashKind.FEE }.sumOf { it.amount },
            taxes = (trades.sumOf { it.tax * it.fxRate }).roundToLong() + cash.filter { it.kind.isIncome }.sumOf { it.tax },
            bought = trades.filter { it.side == Side.BUY }.sumOf { it.grossWon }.roundToLong(),
            sold = trades.filter { it.side == Side.SELL }.sumOf { it.grossWon }.roundToLong(),
            deposits = cash.filter { it.kind == CashKind.DEPOSIT }.sumOf { it.amount },
            withdrawals = cash.filter { it.kind == CashKind.WITHDRAW }.sumOf { it.amount },
            trades = trades.size,
            sells = sales.size,
            wins = wins.size,
            losses = losses.size,
            grossWin = wins.sumOf { it.pnlWon }.roundToLong(),
            grossLoss = -losses.sumOf { it.pnlWon }.roundToLong(),
            best = sales.filter { it.win }.maxByOrNull { it.pnlWon },
            worst = sales.filter { it.loss }.minByOrNull { it.pnlWon },
            bySymbol = bySymbol,
            byEmotion = byEmotion,
            byRules = byRules,
            byWeekday = byWeekday,
            winDays = daily.values.count { it > 0 },
            lossDays = daily.values.count { it < 0 },
            longestWinStreak = winStreak,
            longestLossStreak = lossStreak,
            averageHoldingDays = if (held.isEmpty()) null else held.average(),
            journalDays = book.journal.count { inRange(it.date) && !it.isEmpty },
        )
    }

    fun month(book: InvestBook, result: PortfolioResult, month: YearMonth): PeriodStats =
        period(book, result, month.atDay(1), month.atEndOfMonth())

    fun year(book: InvestBook, result: PortfolioResult, year: Int): PeriodStats =
        period(book, result, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))

    /** [INV-06] Each month of [year]: what it earned (sales and payouts). */
    fun monthly(book: InvestBook, result: PortfolioResult, year: Int): List<Pair<YearMonth, Long>> {
        val daily = Portfolio.dailyPnl(result, book.cash)
        return (1..12).map { m ->
            val ym = YearMonth.of(year, m)
            ym to daily.filterKeys { YearMonth.from(it) == ym }.values.sum()
        }
    }

    /** Longest runs of days in a row (trading days with a result, in date order) that made and lost money. */
    private fun streaks(daily: Map<LocalDate, Long>): Pair<Int, Int> {
        var win = 0; var loss = 0; var bestWin = 0; var bestLoss = 0
        for ((_, v) in daily.toSortedMap()) {
            when {
                v > 0 -> { win++; loss = 0 }
                v < 0 -> { loss++; win = 0 }
                else -> { win = 0; loss = 0 }
            }
            bestWin = maxOf(bestWin, win)
            bestLoss = maxOf(bestLoss, loss)
        }
        return bestWin to bestLoss
    }
}
