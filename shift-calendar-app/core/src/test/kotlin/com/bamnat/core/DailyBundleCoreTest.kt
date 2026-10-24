package com.bamnat.core

import com.bamnat.core.invest.InvestAccount
import com.bamnat.core.invest.InvestBook
import com.bamnat.core.invest.InvestFees
import com.bamnat.core.invest.InvestMonthSummary
import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Trade
import com.bamnat.core.money.ShopCapture
import com.bamnat.core.pay.RateHint
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.QuickOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** 1.22 묶음 E parts that run in core: INV-33, INV-34, DAILY-10, DAILY-13, DAILY-16. */
class DailyBundleCoreTest {
    @Test
    fun accountFeeForOneMarketWinsOverItsOwnAndTheMarketsDefault() {
        val a = InvestAccount(id = 3, name = "국내", feeRate = 0.015, marketFees = mapOf(Market.US_STOCK to 0.07))
        assertEquals(0.07, a.feeFor(Market.US_STOCK), 0.0)
        assertEquals(0.015, a.feeFor(Market.KR_STOCK), 0.0)
        assertEquals(Market.CRYPTO.defaultFeeRate, InvestAccount(name = "x").feeFor(Market.CRYPTO), 0.0)
        // Kept as one settings line; nonsense and rates over 5% are dropped.
        val text = InvestAccount.encodeFees(mapOf(Market.US_STOCK to 0.07, Market.KR_ETF to 0.01))
        assertEquals("KR_ETF=0.01,US_STOCK=0.07", text)
        assertEquals(mapOf(Market.KR_ETF to 0.01, Market.US_STOCK to 0.07), InvestAccount.decodeFees(text))
        assertEquals(emptyMap<Market, Double>(), InvestAccount.decodeFees("NOPE=1,US_STOCK=9,KR_STOCK=x"))
        assertEquals("fees.3", InvestAccount.feesKey(3))
        // The trade sheet's suggested fee uses the account's rate for that market.
        val fee = InvestFees.suggest(Market.US_STOCK, Side.BUY, 10.0, 100.0, a).fee
        assertEquals(1000 * 0.07 / 100, fee, 1e-9)
    }

    @Test
    fun monthSummaryCountsTheMonthAndHidesAmountsWhenAsked() {
        val book = InvestBook(trades = listOf(
            Trade(id = 1, accountId = 1, date = LocalDate.of(2026, 9, 30), symbol = "A", side = Side.BUY, quantity = 10.0, price = 1000.0),
            Trade(id = 2, accountId = 1, date = LocalDate.of(2026, 10, 2), symbol = "A", side = Side.BUY, quantity = 10.0, price = 2000.0),
            Trade(id = 3, accountId = 1, date = LocalDate.of(2026, 10, 20), symbol = "A", side = Side.SELL, quantity = 5.0, price = 3000.0),
        ))
        val m = InvestMonthSummary.of(book, YearMonth.of(2026, 10))
        assertEquals(1, m.buys)
        assertEquals(1, m.sells)
        assertEquals(20_000L, m.bought)
        assertEquals(15_000L, m.sold)
        assertTrue(m.text(hide = false).startsWith("10월 매수 1건 · 매도 1건 · 산 금액 20,000원 · 실현 손익 +"))
        assertEquals("10월 매수 1건 · 매도 1건", m.text(hide = true))
        assertTrue(InvestMonthSummary.of(book, YearMonth.of(2026, 8)).isEmpty)
        // 20:00 on the month's last day; past it, next month's.
        assertEquals(LocalDateTime.of(2026, 10, 31, 20, 0), InvestMonthSummary.next(LocalDateTime.of(2026, 10, 22, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 11, 30, 20, 0), InvestMonthSummary.next(LocalDateTime.of(2026, 10, 31, 20, 0)))
        assertEquals(LocalDateTime.of(2027, 2, 28, 20, 0), InvestMonthSummary.noticeAt(YearMonth.of(2027, 2)))
    }

    @Test
    fun shopSalesWaitingForALookKeepEachNoticeOnceAndRoundTrip() {
        val p = ShopCapture.Pending("k1", LocalDate.of(2026, 10, 21), ShopCapture.Sale(ShopCapture.Bucket.CARD, 12_000, "토스\t페이"))
        val back = ShopCapture.Pending.decode(p.encode())
        assertEquals(p.copy(sale = p.sale.copy(app = "토스 페이")), back)
        assertNull(ShopCapture.Pending.decode("broken"))
        val list = ShopCapture.Pending.add(ShopCapture.Pending.add(emptyList(), p), p)
        assertEquals(1, list.size)
        val many = (1..70).fold(emptyList<ShopCapture.Pending>()) { l, i -> ShopCapture.Pending.add(l, p.copy(key = "k$i")) }
        assertEquals(ShopCapture.Pending.MAX, many.size)
        assertEquals("k70", many.last().key)
    }

    @Test
    fun rateHintShowsOnlyAWideGapOfTheSameKindOfPay() {
        val groups = listOf(
            RateHint.Group("construction", "31", "형틀목수", "day", 2026, 5, 200_000, 250_000, 280_000),
            RateHint.Group("construction", "31", "형틀목수", "day", 2025, 9, 190_000, 240_000, 270_000),
            RateHint.Group("hourly", "31", "편의점", "hour", 2026, 2, 10_030, 10_500, 11_000),
            RateHint.Group("nurse", "11", "병동", "month", 2026, 4, 3_000_000, 3_500_000, 4_000_000),
        )
        val carpenter = RateHint.Mine("construction", "31", "형틀목수")
        val h = RateHint.of(groups, WageType.DAILY, 200_000, carpenter)!!
        assertEquals(2026, h.group.year)
        assertEquals(-20, h.percent)
        assertTrue(h.below)
        assertTrue(h.text("일당", "경기").startsWith("경기 형틀목수 광장 시세"))
        assertTrue(h.text("일당", "경기").contains("20% 낮아요"))
        // A 5% gap, too few people, no user to compare: nothing to say.
        assertNull(RateHint.of(groups, WageType.DAILY, 262_000, carpenter))
        assertNull(RateHint.of(groups, WageType.HOURLY, 8_000, RateHint.Mine("hourly", "31")))
        assertNull(RateHint.of(groups, WageType.DAILY, 200_000, null))
        assertNull(RateHint.of(emptyList(), WageType.DAILY, 200_000, carpenter))
        // 월급 compares too, with the month groups of the same job and region.
        assertEquals(-20, RateHint.of(groups, WageType.MONTHLY, 2_800_000, RateHint.Mine("nurse", "11"))!!.percent)
    }

    @Test
    fun rateHintNeverComparesAnotherJobRegionOrUnit() {
        val groups = listOf(
            RateHint.Group("construction", "31", "형틀목수", "day", 2026, 5, 230_000, 250_000, 280_000),
            RateHint.Group("construction", "31", "철근공", "day", 2026, 4, 220_000, 240_000, 260_000),
            RateHint.Group("nurse", "11", "병동", "day", 2026, 6, 180_000, 200_000, 220_000),
        )
        // A 병원 간호사 on 일당 with only 경기 형틀목수 on the phone: nothing (the 1.22 screenshot 496 case).
        val nurse = RateHint.Mine(RateHint.jobOf(com.bamnat.core.pay.Occupation.NURSE)!!, "31")
        assertNull(RateHint.of(groups.take(2), WageType.DAILY, 150_000, nurse))
        // The right job in another region, or the right group in another unit.
        assertNull(RateHint.of(groups, WageType.DAILY, 150_000, RateHint.Mine("nurse", "31")))
        assertNull(RateHint.of(groups, WageType.HOURLY, 15_000, RateHint.Mine("nurse", "11")))
        // Same job and region but two trades and none of the user's own: left alone; with the trade, compared.
        assertNull(RateHint.of(groups, WageType.DAILY, 150_000, RateHint.Mine("construction", "31")))
        assertEquals("철근공", RateHint.of(groups, WageType.DAILY, 150_000, RateHint.Mine("construction", "31", "철근공"))!!.group.trade)
        // 직접 설정 has no job of its own; the user's own shared rate has to say it.
        assertNull(RateHint.jobOf(com.bamnat.core.pay.Occupation.CUSTOM))
        assertNull(RateHint.of(groups, WageType.DAILY, 150_000, RateHint.Mine("", "31")))
    }

    @Test
    fun quickActionsPutTheMostUsedShiftFirstAndKeepTheRestInOrder() {
        val types = listOf("주", "야", "비", "휴")
        assertEquals(types, QuickOrder.byUse(types, { it }, emptyList()))
        assertEquals(listOf("비", "야", "주", "휴"), QuickOrder.byUse(types, { it }, listOf("야", "비", "비")))
        assertFalse(QuickOrder.byUse(types, { it }, listOf("없음")).isEmpty())
    }
}
