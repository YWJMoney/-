package com.bamnat.core.invest

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [INV-04] One holding in one account (or, with [accountId] null, in all accounts together): what is
 * held, what it cost with the buying fees, the price the user typed and what selling it earned so far.
 * Costs follow the moving average (이동평균법), as Korean brokers show 평균단가.
 */
data class Holding(
    val key: String,
    val symbol: String,
    val name: String,
    val market: Market,
    val accountId: Long?,
    val quantity: Double,
    /** What the shares still held cost in won, buying fees and tax included. */
    val costWon: Double,
    /** The same in the holding's own currency. */
    val costNative: Double,
    val quote: Quote?,
    /** Won per unit of the quote's currency used for the value (1 for won). */
    val fx: Double,
    val realizedWon: Double,
    val dividendsWon: Long,
    /** When the shares held now started to be bought (the holding last went up from nothing). */
    val openedOn: LocalDate?,
    val trades: Int,
) {
    val title: String get() = name.ifBlank { symbol }
    val held: Boolean get() = quantity > EPS
    /** 평균단가 in the holding's currency, fees included. */
    val averagePrice: Double get() = if (held) costNative / quantity else 0.0
    /** What it is worth at the typed price; null without one. */
    val valueWon: Double? get() = quote?.let { quantity * it.price * fx }
    val unrealizedWon: Double? get() = valueWon?.let { it - costWon }
    val unrealizedRate: Double? get() = unrealizedWon?.takeIf { costWon > 0 }?.let { it / costWon }
    /** Sold, held and paid out together. */
    val totalPnlWon: Double get() = realizedWon + dividendsWon + (unrealizedWon ?: 0.0)
}

/** [INV-04] A sale and what it made: proceeds after fees and tax less what the shares sold had cost. */
data class Realization(
    val tradeId: Long,
    val date: LocalDate,
    val key: String,
    val name: String,
    val market: Market,
    val accountId: Long,
    val quantity: Double,
    val proceedsWon: Double,
    val costWon: Double,
    val openedOn: LocalDate?,
    val emotions: Set<Emotion>,
    val followedRules: Boolean?,
) {
    val pnlWon: Double get() = proceedsWon - costWon
    val rate: Double? get() = if (costWon > 0) pnlWon / costWon else null
    val win: Boolean get() = pnlWon > 0.5
    val loss: Boolean get() = pnlWon < -0.5
    val holdingDays: Long? get() = openedOn?.let { ChronoUnit.DAYS.between(it, date) }
}

/** [INV-02][INV-15] One account: what went in and out, the cash left and what its holdings are worth. */
data class AccountSummary(
    val account: InvestAccount?,
    val accountId: Long,
    val deposits: Long,
    val withdrawals: Long,
    val cash: Long,
    val cost: Long,
    val value: Long,
    val realized: Long,
    val income: Long,
    val fees: Long,
    val taxes: Long,
) {
    /** 원금: money put in less money taken out. */
    val principal: Long get() = deposits - withdrawals
    val totalAssets: Long get() = cash + value
    val pnl: Long get() = totalAssets - principal
    val rate: Double? get() = if (principal > 0) pnl.toDouble() / principal else null
}

/** [INV-05] Everything together, the numbers the summary card shows. */
data class PortfolioSummary(
    val deposits: Long,
    val withdrawals: Long,
    val cash: Long,
    val cost: Long,
    /** Holdings at the typed prices; a holding without one counts at what it cost. */
    val value: Long,
    val realized: Long,
    /** Dividends and interest after tax. */
    val income: Long,
    val fees: Long,
    val taxes: Long,
    /** Holdings still without a typed price. */
    val missingQuotes: List<String>,
) {
    val principal: Long get() = deposits - withdrawals
    val totalAssets: Long get() = cash + value
    /** 총 손익: everything now less everything put in. */
    val pnl: Long get() = totalAssets - principal
    val unrealized: Long get() = value - cost
    /** [INV-05] 원금 대비 수익률; null until some money was put in. */
    val simpleReturn: Double? get() = if (principal > 0) pnl.toDouble() / principal else null
    /** Cash below zero: trades were written without the deposit that paid for them. */
    val cashShort: Boolean get() = cash < 0

    companion object {
        val EMPTY = PortfolioSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList())
    }
}

data class PortfolioResult(
    /** Per account and holding, ones sold out included. */
    val holdings: List<Holding>,
    /** Per holding over every account. */
    val byKey: List<Holding>,
    val realizations: List<Realization>,
    val accounts: List<AccountSummary>,
    val summary: PortfolioSummary,
) {
    val held: List<Holding> get() = byKey.filter { it.held }

    companion object {
        val EMPTY = PortfolioResult(emptyList(), emptyList(), emptyList(), emptyList(), PortfolioSummary.EMPTY)
    }
}

const val EPS = 1e-9

object Portfolio {
    /** Trades in the order they happened; a trade without a time counts before timed ones that day. */
    val ORDER: Comparator<Trade> = compareBy<Trade>({ it.date }, { it.minute ?: -1 }, { it.id })

    /** [INV-04][INV-05] The whole book worked out, as of [upTo] (everything when null). */
    fun build(book: InvestBook, upTo: LocalDate? = null, useQuotes: Boolean = true): PortfolioResult {
        val trades = book.trades.filter { upTo == null || !it.date.isAfter(upTo) }.sortedWith(ORDER)
        val cash = book.cash.filter { upTo == null || !it.date.isAfter(upTo) }
        if (trades.isEmpty() && cash.isEmpty()) return PortfolioResult.EMPTY
        val quotes = if (useQuotes) book.quotes.associateBy { it.key } else emptyMap()

        class State(val accountId: Long, val key: String, var symbol: String, var name: String, var market: Market) {
            var qty = 0.0
            var costWon = 0.0
            var costNative = 0.0
            var realized = 0.0
            var openedOn: LocalDate? = null
            var trades = 0
        }
        val states = LinkedHashMap<Pair<Long, String>, State>()
        val realizations = ArrayList<Realization>()
        for (t in trades) {
            val s = states.getOrPut(t.accountId to t.key) { State(t.accountId, t.key, t.symbol, t.name, t.market) }
            if (t.symbol.isNotBlank()) s.symbol = t.symbol.trim()
            if (t.name.isNotBlank()) s.name = t.name.trim()
            s.market = t.market
            s.trades++
            if (t.quantity <= 0) continue
            when (t.side) {
                Side.BUY -> {
                    if (s.qty <= EPS) s.openedOn = t.date
                    s.qty += t.quantity
                    s.costWon += t.grossWon + t.costsWon
                    s.costNative += t.gross + t.fee + t.tax
                }
                Side.SELL -> {
                    val sold = minOf(t.quantity, s.qty)
                    val proceeds = t.grossWon - t.costsWon
                    // A sale of more than was written as bought: the extra has no known cost, so it makes nothing.
                    val share = if (t.quantity > 0) sold / t.quantity else 0.0
                    val ratio = if (s.qty > EPS) sold / s.qty else 0.0
                    val cost = s.costWon * ratio
                    val proceedsKnown = proceeds * share
                    realizations += Realization(t.id, t.date, t.key, s.name.ifBlank { s.symbol }, t.market, t.accountId, t.quantity,
                        proceeds, cost + (proceeds - proceedsKnown), s.openedOn, t.emotions, t.followedRules)
                    s.realized += proceedsKnown - cost
                    s.costWon -= cost
                    s.costNative -= s.costNative * ratio
                    s.qty -= sold
                    if (s.qty <= EPS) {
                        s.qty = 0.0; s.costWon = 0.0; s.costNative = 0.0; s.openedOn = null
                    }
                }
            }
        }
        val dividends = cash.filter { it.kind == CashKind.DIVIDEND && it.symbol.isNotBlank() }
            .groupBy { it.accountId to holdingKey(it.symbol, "") }.mapValues { (_, l) -> l.sumOf { it.amount - it.tax } }
        val fx = { m: Market -> if (m.foreign) book.settings.usdKrw else 1.0 }
        val holdings = states.values.map { s ->
            Holding(s.key, s.symbol, s.name, s.market, s.accountId, s.qty, s.costWon, s.costNative, quotes[s.key], fx(s.market),
                s.realized, dividends[s.accountId to s.key] ?: 0L, s.openedOn, s.trades)
        }
        val byKey = holdings.groupBy { it.key }.map { (key, list) ->
            val last = list.maxByOrNull { it.trades }!!
            Holding(key, last.symbol, list.firstOrNull { it.name.isNotBlank() }?.name.orEmpty(), last.market, null,
                list.sumOf { it.quantity }, list.sumOf { it.costWon }, list.sumOf { it.costNative }, quotes[key], last.fx,
                list.sumOf { it.realizedWon }, list.sumOf { it.dividendsWon }, list.mapNotNull { it.openedOn }.minOrNull(), list.sumOf { it.trades })
        }.sortedWith(compareByDescending<Holding> { it.held }.thenByDescending { it.valueWon ?: it.costWon }.thenBy { it.title })

        val accountIds = (book.accounts.map { it.id } + trades.map { it.accountId } + cash.map { it.accountId }).distinct()
        val summaries = accountIds.map { id ->
            val ts = trades.filter { it.accountId == id }
            val cs = cash.filter { it.accountId == id }
            val hs = holdings.filter { it.accountId == id }
            AccountSummary(
                account = book.account(id), accountId = id,
                deposits = cs.filter { it.kind == CashKind.DEPOSIT }.sumOf { it.amount },
                withdrawals = cs.filter { it.kind == CashKind.WITHDRAW }.sumOf { it.amount },
                cash = (ts.sumOf { it.cashWon } + cs.sumOf { it.cashWon }).roundToLong(),
                cost = hs.sumOf { it.costWon }.roundToLong(),
                value = hs.sumOf { it.valueWon ?: it.costWon }.roundToLong(),
                realized = hs.sumOf { it.realizedWon }.roundToLong(),
                income = cs.filter { it.kind.isIncome }.sumOf { it.amount - it.tax },
                fees = (ts.sumOf { it.fee * it.fxRate } + cs.filter { it.kind == CashKind.FEE }.sumOf { it.amount }).roundToLong(),
                taxes = (ts.sumOf { it.tax * it.fxRate } + cs.filter { it.kind.isIncome }.sumOf { it.tax }).roundToLong(),
            )
        }.filter { it.account != null || it.deposits != 0L || it.cash != 0L || it.cost != 0L }
        val summary = PortfolioSummary(
            deposits = summaries.sumOf { it.deposits },
            withdrawals = summaries.sumOf { it.withdrawals },
            cash = summaries.sumOf { it.cash },
            cost = summaries.sumOf { it.cost },
            value = summaries.sumOf { it.value },
            realized = summaries.sumOf { it.realized },
            income = summaries.sumOf { it.income },
            fees = summaries.sumOf { it.fees },
            taxes = summaries.sumOf { it.taxes },
            missingQuotes = byKey.filter { it.held && it.quote == null }.map { it.title },
        )
        return PortfolioResult(holdings, byKey, realizations, summaries, summary)
    }

    /**
     * [INV-12] Sales matched to the oldest shares first (선입선출법), as the tax on foreign shares is
     * worked out; the summary's own figures use the moving average.
     */
    fun fifo(trades: List<Trade>): List<Realization> {
        class Lot(var qty: Double, val costPerUnit: Double, val date: LocalDate)
        val lots = HashMap<Pair<Long, String>, ArrayDeque<Lot>>()
        val out = ArrayList<Realization>()
        for (t in trades.sortedWith(ORDER)) {
            if (t.quantity <= 0) continue
            val queue = lots.getOrPut(t.accountId to t.key) { ArrayDeque() }
            if (t.side == Side.BUY) {
                queue.addLast(Lot(t.quantity, (t.grossWon + t.costsWon) / t.quantity, t.date))
                continue
            }
            var left = t.quantity
            var cost = 0.0
            val first = queue.firstOrNull()?.date
            while (left > EPS && queue.isNotEmpty()) {
                val lot = queue.first()
                val take = minOf(lot.qty, left)
                cost += take * lot.costPerUnit
                lot.qty -= take
                left -= take
                if (lot.qty <= EPS) queue.removeFirst()
            }
            val proceeds = t.grossWon - t.costsWon
            // As in the average: a part sold with no buy behind it makes nothing.
            if (left > EPS) cost += proceeds * left / t.quantity
            out += Realization(t.id, t.date, t.key, t.title, t.market, t.accountId, t.quantity, proceeds, cost, first, t.emotions, t.followedRules)
        }
        return out
    }

    /**
     * [INV-01][INV-06] What each day earned: sales' gains and losses, dividends and interest after
     * tax, less other costs. This is what a calendar cell shows.
     */
    fun dailyPnl(result: PortfolioResult, cash: List<CashFlow>): Map<LocalDate, Long> {
        val map = HashMap<LocalDate, Double>()
        for (r in result.realizations) map[r.date] = (map[r.date] ?: 0.0) + r.pnlWon
        for (c in cash) when (c.kind) {
            CashKind.DIVIDEND, CashKind.INTEREST -> map[c.date] = (map[c.date] ?: 0.0) + (c.amount - c.tax)
            CashKind.FEE -> map[c.date] = (map[c.date] ?: 0.0) - c.amount
            else -> Unit
        }
        return map.mapValues { it.value.roundToLong() }
    }

    /** [INV-04] Quantity as people write it: no trailing zeros, at most eight decimals (coins). */
    fun quantityText(q: Double): String {
        if (abs(q - q.roundToLong()) < 1e-9) return q.roundToLong().toString()
        return "%.8f".format(java.util.Locale.ROOT, q).trimEnd('0').trimEnd('.')
    }
}
