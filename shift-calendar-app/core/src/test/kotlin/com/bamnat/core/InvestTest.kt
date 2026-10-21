package com.bamnat.core

import com.bamnat.core.invest.AccountType
import com.bamnat.core.invest.AlarmKind
import com.bamnat.core.invest.CashFlow
import com.bamnat.core.invest.CashKind
import com.bamnat.core.invest.Emotion
import com.bamnat.core.invest.InvestAccount
import com.bamnat.core.invest.InvestAlarm
import com.bamnat.core.invest.InvestAlarms
import com.bamnat.core.invest.InvestBook
import com.bamnat.core.invest.InvestCsv
import com.bamnat.core.invest.InvestEvent
import com.bamnat.core.invest.EventKind
import com.bamnat.core.invest.InvestFees
import com.bamnat.core.invest.InvestGoal
import com.bamnat.core.invest.InvestPlans
import com.bamnat.core.invest.InvestReturns
import com.bamnat.core.invest.InvestSettings
import com.bamnat.core.invest.InvestStats
import com.bamnat.core.invest.InvestTaxes
import com.bamnat.core.invest.JournalEntry
import com.bamnat.core.invest.JournalRules
import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Portfolio
import com.bamnat.core.invest.Quote
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Snapshot
import com.bamnat.core.invest.Trade
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.WorkStyle
import com.bamnat.core.work.words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.math.roundToLong

/** [INV-01..INV-21] The investor calendar's sums: holdings, gains, cash, returns, fees, taxes, alarms and files. */
class InvestTest {
    private fun d(day: Int, month: Int = 3, year: Int = 2026) = LocalDate.of(year, month, day)
    private val general = InvestAccount(1, "일반", AccountType.GENERAL)
    private val isa = InvestAccount(2, "ISA", AccountType.ISA)
    private val pension = InvestAccount(3, "연금", AccountType.PENSION)
    private fun buy(day: Int, q: Double, p: Double, fee: Double = 0.0, symbol: String = "005930", id: Long = 0, account: Long = 1, month: Int = 3) =
        Trade(id, account, d(day, month), symbol = symbol, name = "삼성전자", side = Side.BUY, quantity = q, price = p, fee = fee)
    private fun sell(day: Int, q: Double, p: Double, fee: Double = 0.0, tax: Double = 0.0, symbol: String = "005930", id: Long = 0, account: Long = 1, month: Int = 3) =
        Trade(id, account, d(day, month), symbol = symbol, name = "삼성전자", side = Side.SELL, quantity = q, price = p, fee = fee, tax = tax)
    private fun deposit(day: Int, amount: Long, account: Long = 1, month: Int = 3) = CashFlow(0, account, d(day, month), kind = CashKind.DEPOSIT, amount = amount)

    @Test
    fun investIsACalendarKindWithItsOwnWords() {
        assertTrue(WorkStyle.INVEST in WorkStyle.entries)
        assertEquals(CalendarFocus.INVEST, CalendarFocus.of(WorkStyle.INVEST))
        assertEquals("투자·수익", WorkStyle.INVEST.words().tab)
        assertEquals("매매 기록", CalendarFocus.INVEST.addLabel)
        assertFalse(WorkStyle.INVEST.usesPayEngine)
        assertTrue(CalendarFocus.INVEST.moneyFirst)
    }

    @Test
    fun averageCostIncludesBuyingFeesAndSalesRealizeAgainstIt() {
        val book = InvestBook(
            accounts = listOf(general),
            cash = listOf(deposit(2, 2_000_000)),
            trades = listOf(
                buy(3, 10.0, 70_000.0, fee = 100.0, id = 1),
                buy(4, 10.0, 80_000.0, fee = 100.0, id = 2),
                sell(5, 5.0, 90_000.0, fee = 50.0, tax = 900.0, id = 3),
            ),
        )
        val r = Portfolio.build(book)
        val h = r.byKey.single()
        assertEquals(15.0, h.quantity, 1e-9)
        // (700,100 + 800,100) / 20 = 75,010 each; 5 sold cost 375,050.
        assertEquals(75_010.0, h.averagePrice, 1e-6)
        val sale = r.realizations.single()
        assertEquals(450_000.0 - 950.0, sale.proceedsWon, 1e-6)
        assertEquals(375_050.0, sale.costWon, 1e-6)
        assertEquals(74_000L, sale.pnlWon.roundToLong())
        assertEquals(LocalDate.of(2026, 3, 3), sale.openedOn)
        assertEquals(2L, sale.holdingDays)
        // Cash: 2,000,000 − 700,100 − 800,100 + 449,050.
        assertEquals(948_850L, r.summary.cash)
        assertEquals(2_000_000L, r.summary.principal)
    }

    @Test
    fun typedPriceGivesValueUnrealizedAndSimpleReturn() {
        val book = InvestBook(
            accounts = listOf(general),
            cash = listOf(deposit(2, 1_000_000)),
            trades = listOf(buy(3, 10.0, 50_000.0, id = 1)),
            quotes = listOf(Quote("005930", 60_000.0, d(10))),
        )
        val s = Portfolio.build(book).summary
        assertEquals(500_000L, s.cash)
        assertEquals(600_000L, s.value)
        assertEquals(100_000L, s.unrealized)
        assertEquals(1_100_000L, s.totalAssets)
        assertEquals(0.10, s.simpleReturn!!, 1e-9)
        assertTrue(s.missingQuotes.isEmpty())
    }

    @Test
    fun principalReactsToDepositsAndWithdrawals() {
        // [INV-05] "원금을 입력했을 때": the same holdings, more money in, a lower return at once.
        val base = InvestBook(
            accounts = listOf(general),
            cash = listOf(deposit(2, 1_000_000)),
            trades = listOf(buy(3, 10.0, 50_000.0, id = 1)),
            quotes = listOf(Quote("005930", 60_000.0, d(10))),
        )
        val more = base.copy(cash = base.cash + deposit(11, 1_000_000) + CashFlow(0, 1, d(12), kind = CashKind.WITHDRAW, amount = 500_000))
        val s = Portfolio.build(more).summary
        assertEquals(1_500_000L, s.principal)
        assertEquals(1_000_000L, s.cash)
        assertEquals(100_000L, s.pnl)
        assertEquals(100_000.0 / 1_500_000, s.simpleReturn!!, 1e-9)
    }

    @Test
    fun holdingWithoutPriceCountsAtCostAndIsNamed() {
        val book = InvestBook(accounts = listOf(general), cash = listOf(deposit(2, 1_000_000)), trades = listOf(buy(3, 10.0, 50_000.0, id = 1)))
        val s = Portfolio.build(book).summary
        assertEquals(500_000L, s.value)
        assertEquals(listOf("삼성전자"), s.missingQuotes)
        assertEquals(0L, s.pnl)
    }

    @Test
    fun sellingEverythingClosesTheHoldingAndOversellMakesNothingExtra() {
        val book = InvestBook(trades = listOf(buy(3, 10.0, 1_000.0, id = 1), sell(4, 12.0, 1_500.0, id = 2)))
        val r = Portfolio.build(book)
        assertFalse(r.byKey.single().held)
        // Only the 10 bought made money: 10 × 500.
        assertEquals(5_000L, r.realizations.single().pnlWon.roundToLong())
    }

    @Test
    fun foreignTradesUseTheirRateAndTheTypedDollar() {
        val t = Trade(1, 1, d(3), symbol = "AAPL", market = Market.US_STOCK, side = Side.BUY, quantity = 2.0, price = 200.0, fxRate = 1_300.0, fee = 1.0)
        val book = InvestBook(trades = listOf(t), quotes = listOf(Quote("AAPL", 250.0, d(10), market = Market.US_STOCK)),
            settings = InvestSettings(usdKrw = 1_400.0))
        val h = Portfolio.build(book).byKey.single()
        assertEquals(401 * 1_300.0, h.costWon, 1e-6)
        assertEquals(200.5, h.averagePrice, 1e-9)
        assertEquals(2 * 250 * 1_400.0, h.valueWon!!, 1e-6)
    }

    @Test
    fun fifoMatchesOldestSharesFirst() {
        val trades = listOf(buy(3, 10.0, 100.0, id = 1), buy(4, 10.0, 200.0, id = 2), sell(5, 10.0, 300.0, id = 3))
        assertEquals(2_000L, Portfolio.fifo(trades).single().pnlWon.roundToLong())
        // The average says 1,500.
        assertEquals(1_500L, Portfolio.build(InvestBook(trades = trades)).realizations.single().pnlWon.roundToLong())
    }

    @Test
    fun dayPnlAddsSalesAndDividendsLessOtherCosts() {
        val book = InvestBook(
            trades = listOf(buy(3, 10.0, 1_000.0, id = 1), sell(5, 5.0, 1_200.0, id = 2), sell(6, 5.0, 900.0, id = 3)),
            cash = listOf(
                CashFlow(0, 1, d(5), kind = CashKind.DIVIDEND, amount = 1_000, tax = 154, symbol = "005930"),
                CashFlow(0, 1, d(7), kind = CashKind.FEE, amount = 300),
            ),
        )
        val daily = Portfolio.dailyPnl(Portfolio.build(book), book.cash)
        assertEquals(1_000L + 846, daily[d(5)])
        assertEquals(-500L, daily[d(6)])
        assertEquals(-300L, daily[d(7)])
    }

    @Test
    fun monthStatsCountWinsSymbolsEmotionsAndRules() {
        val book = InvestBook(
            trades = listOf(
                buy(2, 10.0, 1_000.0, id = 1),
                sell(3, 5.0, 1_400.0, id = 2).copy(emotions = setOf(Emotion.CALM), followedRules = true),
                sell(4, 5.0, 800.0, id = 3).copy(emotions = setOf(Emotion.RUSHED), followedRules = false),
                buy(5, 1.0, 100.0, symbol = "AAA", id = 4).copy(name = "에이"),
                sell(6, 1.0, 150.0, symbol = "AAA", id = 5).copy(name = "에이", emotions = setOf(Emotion.CALM), followedRules = true),
            ),
        )
        val r = Portfolio.build(book)
        val s = InvestStats.month(book, r, YearMonth.of(2026, 3))
        assertEquals(3, s.sells)
        assertEquals(2, s.wins)
        assertEquals(1, s.losses)
        assertEquals(2.0 / 3, s.winRate!!, 1e-9)
        assertEquals(2_000L + 50 - 1_000, s.realized)
        assertEquals(2_050L, s.grossWin)
        assertEquals(1_000L, s.grossLoss)
        assertEquals(2.05, s.profitFactor!!, 1e-9)
        assertEquals(1_025L, s.averageWin)
        assertEquals(1.025, s.payoff!!, 1e-9)
        assertEquals("삼성전자", s.bySymbol.first().name)
        assertEquals(1_000L, s.bySymbol.first().realized)
        val calm = s.byEmotion.first { it.label == "차분" }
        assertEquals(2, calm.sells)
        assertEquals(1.0, calm.winRate!!, 1e-9)
        assertEquals(2, s.byRules.first { it.label == "원칙 지킴" }.wins)
        assertEquals(-1_000L, s.byRules.first { it.label == "원칙 어김" }.pnl)
        assertEquals(2, s.winDays)
        assertEquals(1, s.lossDays)
        assertEquals(s.realized, s.byWeekday.values.sum())
        assertEquals(s.realized, InvestStats.monthly(book, r, 2026)[2].second)
    }

    @Test
    fun feesAndSellTaxAreSuggestedByMarket() {
        val kr = InvestFees.suggest(Market.KR_STOCK, Side.SELL, 10.0, 70_000.0, null)
        assertEquals(105.0, kr.fee, 1e-9)
        assertEquals(1_400.0, kr.tax, 1e-9)
        assertEquals(0.0, InvestFees.suggest(Market.KR_ETF, Side.SELL, 10.0, 10_000.0, null).tax, 1e-9)
        assertEquals(0.0, InvestFees.suggest(Market.KR_STOCK, Side.BUY, 10.0, 70_000.0, null).tax, 1e-9)
        assertEquals(1.25, InvestFees.suggest(Market.US_STOCK, Side.BUY, 1.0, 500.0, null).fee, 1e-9)
        // The account's own rate wins.
        assertEquals(0.0, InvestFees.suggest(Market.KR_STOCK, Side.BUY, 10.0, 70_000.0, general.copy(feeRate = 0.0)).fee, 1e-9)
        assertEquals(3.0, InvestFees.unitsFor(300_000, 100_000.0, Market.KR_STOCK, 1.0)!!, 1e-9)
        assertEquals(0.003, InvestFees.unitsFor(300_000, 100_000_000.0, Market.CRYPTO, 1.0)!!, 1e-12)
        assertNull(InvestFees.unitsFor(50_000, 100_000.0, Market.KR_STOCK, 1.0))
        assertEquals(1_540L, InvestTaxes.dividendTax(10_000, Market.KR_STOCK))
        assertEquals(1_500L, InvestTaxes.dividendTax(10_000, Market.US_STOCK))
    }

    @Test
    fun taxEstimateUsesDeductionsAndLeavesDeferredAccountsOut() {
        fun us(day: Int, side: Side, q: Double, p: Double, id: Long, account: Long = 1) =
            Trade(id, account, d(day), symbol = "TSLA", market = Market.US_STOCK, side = side, quantity = q, price = p, fxRate = 1_000.0)
        val book = InvestBook(
            accounts = listOf(general, isa, pension),
            trades = listOf(
                us(2, Side.BUY, 10.0, 100.0, 1), us(3, Side.SELL, 10.0, 500.0, 2),
                Trade(3, 1, d(4), symbol = "BTC", market = Market.CRYPTO, side = Side.BUY, quantity = 1.0, price = 1_000_000.0),
                Trade(4, 1, d(5), symbol = "BTC", market = Market.CRYPTO, side = Side.SELL, quantity = 1.0, price = 4_000_000.0),
                buy(6, 10.0, 10_000.0, id = 5, account = 2), sell(7, 10.0, 40_000.0, id = 6, account = 2),
                buy(6, 1.0, 10_000.0, symbol = "P", id = 7, account = 3), sell(7, 1.0, 20_000.0, symbol = "P", id = 8, account = 3),
            ),
            cash = listOf(CashFlow(0, 1, d(8), kind = CashKind.DIVIDEND, amount = 100_000, tax = 15_400, symbol = "005930")),
        )
        val t = InvestTaxes.estimate(book, Portfolio.build(book), 2026)
        assertEquals(4_000_000L, t.foreignGain)
        assertEquals(1_500_000L, t.foreignTaxable)
        assertEquals(330_000L, t.foreignTax)
        assertEquals(0L, t.foreignDeductionLeft)
        assertEquals(3_000_000L, t.cryptoGain)
        assertFalse(t.cryptoTaxed)
        assertEquals(110_000L, t.cryptoTax)
        assertTrue(t.hasIsa)
        assertEquals(300_000L, t.isaGain)
        assertEquals(0L, t.isaTaxOver)
        assertEquals(10_000L, t.deferredGain)
        assertEquals(15_400L, t.dividendTax)
        assertEquals(100_000L, t.financialIncome)
        assertFalse(t.financialNear)
        assertTrue(InvestTaxes.estimate(book, Portfolio.build(book), 2027).cryptoTaxed)
    }

    @Test
    fun moneyWeightedCountsWhenMoneyCameIn() {
        // 1,000,000 all month, doubled to 2,000,000 by day 16 with another 1,000,000 in, ends at 2,100,000.
        val book = InvestBook(
            accounts = listOf(general),
            cash = listOf(deposit(1, 1_000_000, month = 2), deposit(16, 1_000_000, month = 4)),
            snapshots = listOf(Snapshot(d(31), 1_000_000, 1_000_000)),
        )
        val live = Portfolio.build(book).summary.copy(cash = 2_100_000)
        val r = InvestReturns.moneyWeighted(book, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 30), live)
        assertEquals(1_000_000L, r.startValue)
        assertEquals(2_100_000L, r.endValue)
        assertEquals(100_000L, r.pnl)
        // Weight of day 16 in a 30-day month: 15/30.
        assertEquals(100_000.0 / 1_500_000, r.rate!!, 1e-9)
    }

    @Test
    fun timeWeightedChainsPiecesBetweenSnapshots() {
        // +10% to the snapshot, then 1,000,000 more and +0% after.
        val book = InvestBook(
            accounts = listOf(general),
            cash = listOf(deposit(1, 1_000_000, month = 2), deposit(10, 1_000_000)),
            snapshots = listOf(Snapshot(d(28, month = 2), 1_000_000, 1_000_000), Snapshot(d(9), 1_100_000, 1_000_000)),
        )
        val live = Portfolio.build(book).summary.copy(cash = 2_100_000)
        val r = InvestReturns.timeWeighted(book, d(1), d(31), d(31), live)
        assertEquals(0.10, r.rate!!, 1e-9)
        assertEquals(1_000_000L, r.flows)
        assertEquals(100_000L, r.pnl)
    }

    @Test
    fun valueBetweenSnapshotsFollowsTradesAtCost() {
        val book = InvestBook(
            cash = listOf(deposit(1, 1_000_000), deposit(5, 500_000)),
            snapshots = listOf(Snapshot(d(3), 1_200_000, 1_000_000)),
        )
        assertEquals(1_700_000L, InvestReturns.valueAt(book, d(6), d(20), null))
        assertEquals(1_000_000L, InvestReturns.valueAt(book, d(2), d(20), null))
        assertEquals(1_500_000L, InvestReturns.principalAt(book, d(6)))
        val s = Portfolio.build(book).summary
        assertEquals(Snapshot(d(20), 1_500_000, 1_500_000), InvestReturns.snapshotFor(d(20), s, false))
    }

    @Test
    fun weeklyAlarmsSkipClosedDaysAndMonthlyPlansMoveToTheNextOpenDay() {
        val open = InvestAlarms.default(AlarmKind.MARKET_OPEN)
        // Friday 2026-10-02 after 9:00 → Tuesday 10/6: 10/3 개천절 fell on a Saturday, so 10/5 is 대체공휴일.
        assertEquals(LocalDateTime.of(2026, 10, 6, 9, 0), InvestAlarms.next(open, LocalDateTime.of(2026, 10, 2, 9, 0)))
        // 10/9 한글날 (Friday) is skipped.
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), InvestAlarms.next(open, LocalDateTime.of(2026, 10, 8, 10, 0)))
        // A plan on the 25th: 2026-10-25 is a Sunday → Monday the 26th.
        val plan = InvestAlarms.default(AlarmKind.PLAN)
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 10), InvestAlarms.next(plan, LocalDateTime.of(2026, 10, 20, 12, 0)))
        // On the 31st in a 30-day month it is the 30th.
        val end = plan.copy(monthDay = 31, skipHolidays = false)
        assertEquals(LocalDateTime.of(2026, 11, 30, 9, 10), InvestAlarms.next(end, LocalDateTime.of(2026, 11, 1, 0, 0)))
        val once = InvestAlarm(kind = AlarmKind.TARGET, minute = 600, once = d(5, 11))
        assertNull(InvestAlarms.next(once, LocalDateTime.of(2026, 11, 5, 10, 0)))
        assertNull(InvestAlarms.next(open.copy(enabled = false), LocalDateTime.of(2026, 10, 2, 9, 0)))
        assertEquals(listOf(open), InvestAlarms.dueAt(listOf(open, plan), LocalDateTime.of(2026, 10, 12, 9, 0)))
        assertEquals("평일 09:00", InvestAlarms.describe(open))
        assertEquals("매달 25일 09:10", InvestAlarms.describe(plan))
        val event = InvestEvent(1, d(3, 11), EventKind.EARNINGS, "005930")
        assertEquals(LocalDateTime.of(2026, 11, 3, 8, 30), InvestAlarms.nextAny(emptyList(), listOf(event), LocalDateTime.of(2026, 11, 1, 0, 0)))
        assertTrue(InvestAlarms.marketClosed(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun planDraftFillsQuantityFromAmountAndProgressCounts() {
        val plan = InvestAlarm(7, AlarmKind.PLAN, minute = 550, monthDay = 25, symbol = "360750", name = "TIGER 미국S&P500",
            market = Market.KR_ETF, accountId = 2, amount = 300_000)
        val book = InvestBook(accounts = listOf(general, isa), quotes = listOf(Quote("360750", 21_000.0, d(20, 10))))
        val draft = InvestPlans.draft(plan, book, d(26, 10))
        assertEquals(14.0, draft.quantity!!, 1e-9)
        assertEquals(21_000.0, draft.price!!, 1e-9)
        assertEquals(2L, draft.accountId)
        assertEquals(7L, draft.alarmId)
        assertTrue(InvestPlans.canSaveAtOnce(draft))
        assertFalse(InvestPlans.canSaveAtOnce(InvestPlans.draft(plan, book.copy(quotes = emptyList()), d(26, 10))))
        val trades = listOf(Trade(1, 2, d(26, 9), symbol = "360750", side = Side.BUY, quantity = 14.0, price = 20_000.0, alarmId = 7),
            Trade(2, 2, d(26, 10), symbol = "360750", side = Side.BUY, quantity = 14.0, price = 21_000.0, alarmId = 7))
        val p = InvestPlans.progress(plan, trades, d(27, 10))
        assertEquals(2, p.times)
        assertEquals(574_000L, p.spent)
        assertTrue(p.doneThisPeriod)
        assertFalse(InvestPlans.progress(plan, trades.take(1), d(27, 10)).doneThisPeriod)
    }

    @Test
    fun goalMovesWithPrincipalAndSaysWhenItIsReached() {
        val settings = InvestSettings(goalAmount = 10_000_000, goalReturnPercent = 20.0, monthlyAdd = 500_000, goalDate = d(1, 1, 2027))
        val summary = Portfolio.build(InvestBook(cash = listOf(deposit(1, 5_000_000)))).summary
        val g = InvestGoal.progress(settings, summary, d(20, 10))
        assertEquals(0.5, g.amountRatio!!, 1e-9)
        assertEquals(5_000_000L, g.amountLeft)
        assertEquals(10, g.monthsLeft)
        assertEquals(1_666_667L, g.perMonthToDate)
        assertEquals(0.0, g.returnRatio!!, 1e-9)
        val more = Portfolio.build(InvestBook(cash = listOf(deposit(1, 5_000_000), deposit(2, 5_000_000)))).summary
        assertEquals(0, InvestGoal.progress(settings, more, d(20, 10)).monthsLeft)
    }

    @Test
    fun csvHasHeadersRealizedGainsAndQuotesFormulas() {
        val book = InvestBook(
            accounts = listOf(general),
            trades = listOf(buy(3, 10.0, 1_000.0, id = 1), sell(4, 10.0, 1_100.0, id = 2).copy(memo = "=SUM(A1)", reason = "익절, 목표가")),
            cash = listOf(deposit(2, 100_000)),
        )
        val csv = InvestCsv.trades(book, Portfolio.build(book))
        val lines = csv.removePrefix("﻿").trim().lines()
        assertEquals(InvestCsv.TRADES_HEADER.joinToString(","), lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[2].contains(",1000,"))
        assertTrue(lines[2].contains("'=SUM(A1)"))
        assertTrue(lines[2].contains("\"익절, 목표가\""))
        assertTrue(InvestCsv.cash(book).contains("입금,100000,0,100000"))
        assertEquals("bamnat-invest-trades.csv", InvestCsv.fileName("trades", null, null))
        assertEquals("0.00123", Portfolio.quantityText(0.00123))
        assertEquals("15", Portfolio.quantityText(15.0))
    }

    @Test
    fun aDepositWrittenAheadIsNotTodaysGain() {
        // [INV-05] 1,000,000 in on the 1st, another 1,000,000 written for the 25th; today is the 20th.
        val book = InvestBook(accounts = listOf(general), cash = listOf(deposit(1, 1_000_000), deposit(25, 1_000_000)))
        val built = Portfolio.build(book)
        val live = InvestReturns.liveSummary(book, d(20), built)
        assertEquals(1_000_000L, live.totalAssets)
        assertEquals(1_000_000L, live.principal)
        val mw = InvestReturns.moneyWeighted(book, d(1), d(20), d(20), live)
        assertEquals(0L, mw.pnl)
        assertEquals(0.0, mw.rate!!, 1e-9)
        assertEquals(0.0, InvestReturns.timeWeighted(book, d(1), d(20), d(20), live).rate!!, 1e-9)
        assertEquals(Snapshot(d(20), 1_000_000, 1_000_000), InvestReturns.snapshotFor(d(20), live, false))
        // The whole book's result counted it as a gain before.
        assertEquals(1_000_000L, InvestReturns.moneyWeighted(book, d(1), d(20), d(20), built.summary).pnl)
        // Nothing ahead: the result already built is used as it is.
        val past = book.copy(cash = book.cash.take(1))
        val pastBuilt = Portfolio.build(past)
        assertTrue(InvestReturns.liveSummary(past, d(20), pastBuilt) === pastBuilt.summary)
    }

    @Test
    fun journalKeepsRuleTextAndReadsOlderPositions() {
        val rules = listOf("손절", "몰빵 금지", "계획대로")
        val kept = linkedSetOf("손절", "계획대로")
        val raw = JournalRules.encode(kept)
        assertFalse(JournalRules.isLegacy(raw))
        assertEquals(kept, JournalRules.decode(raw, emptyList()))
        // A single rule, even one that looks like a number, is text.
        assertEquals(setOf("3"), JournalRules.decode(JournalRules.encode(setOf("3")), rules))
        // Older rows kept positions: read with the rules as they stand; ones past the end drop out.
        assertTrue(JournalRules.isLegacy("0,2"))
        assertEquals(kept, JournalRules.decode("2,0,7", rules))
        assertEquals(emptySet<String>(), JournalRules.decode("", rules))
        assertFalse(JournalRules.isLegacy(""))
        // The CSV writes the kept rules by name: in the list's order, then ones since taken off.
        val book = InvestBook(
            journal = listOf(JournalEntry(d(3), review = "복기", checked = linkedSetOf("옛 원칙", "계획대로", "손절"))),
            settings = InvestSettings(principles = rules),
        )
        assertTrue(InvestCsv.journal(book).contains(",손절 / 계획대로 / 옛 원칙,"))
    }

    @Test
    fun otherCostsAreNotIncomeButComeOffThePeriod() {
        val book = InvestBook(
            cash = listOf(
                CashFlow(0, 1, d(5), kind = CashKind.DIVIDEND, amount = 1_000, tax = 154, symbol = "005930"),
                CashFlow(0, 1, d(7), kind = CashKind.FEE, amount = 3_000),
            ),
        )
        val s = InvestStats.month(book, Portfolio.build(book), YearMonth.of(2026, 3))
        assertEquals(846L, s.income)
        assertEquals(3_000L, s.otherCosts)
        assertEquals(3_000L, s.fees)
        assertEquals(846L - 3_000, s.pnl)
        assertEquals(Portfolio.dailyPnl(Portfolio.build(book), book.cash).values.sum(), s.pnl)
        assertFalse(InvestStats.month(book.copy(cash = book.cash.drop(1)), Portfolio.build(book), YearMonth.of(2026, 3)).isEmpty)
    }

    @Test
    fun planDoneLabelFollowsItsStretchAndCoinsCountInUnits() {
        val trade = Trade(1, 1, d(20, 10), symbol = "X", side = Side.BUY, quantity = 1.0, price = 1.0, alarmId = 9)
        fun label(a: InvestAlarm) = InvestPlans.progress(a.copy(id = 9), listOf(trade), d(20, 10)).also { assertTrue(it.doneThisPeriod) }.doneLabel
        assertEquals("이번 달 완료", label(InvestAlarm(kind = AlarmKind.PLAN, minute = 0, monthDay = 20)))
        assertEquals("오늘 완료", label(InvestAlarms.default(AlarmKind.MARKET_OPEN)))
        assertEquals("이번 주 완료", label(InvestAlarm(kind = AlarmKind.PLAN, minute = 0, weekdays = setOf(DayOfWeek.TUESDAY))))
        assertEquals("완료", label(InvestAlarm(kind = AlarmKind.PLAN, minute = 0, once = d(20, 10))))
        assertEquals("개", Market.CRYPTO.unit)
        assertEquals("주", Market.US_STOCK.unit)
    }

    @Test
    fun alarmDescribesCustomDays() {
        val a = InvestAlarm(kind = AlarmKind.CUSTOM, minute = 20 * 60 + 5, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), skipHolidays = false)
        assertEquals("월·목 20:05", InvestAlarms.describe(a))
    }
}
