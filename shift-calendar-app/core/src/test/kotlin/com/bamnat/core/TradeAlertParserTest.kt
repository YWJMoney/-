package com.bamnat.core

import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.TradeAlertParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [INV-30][INV-31] Broker and exchange 체결 알림. The texts are written for the tests from the parts
 * brokers say their 체결 통보 holds (구분, 종목명, 종목코드, 수량, 단가); no real person's alerts.
 */
class TradeAlertParserTest {
    private fun p(text: String, pkg: String = "") = TradeAlertParser.parse(text, pkg)

    @Test fun koreanStockBuy() {
        val a = p("[체결통보] 매수 삼성전자(005930) 10주 체결 단가 71,500원", "com.truefriend.neosmarta")!!
        assertEquals(Side.BUY, a.side)
        assertEquals("005930", a.symbol)
        assertEquals("삼성전자", a.name)
        assertEquals(10.0, a.quantity, 0.0)
        assertEquals(71_500.0, a.price, 0.0)
        assertEquals(Market.KR_STOCK, a.market)
    }

    @Test fun sellWithLabels() {
        val a = p("주식 매도 체결\n종목명: 카카오\n체결수량: 3\n체결단가: 45,200원")!!
        assertEquals(Side.SELL, a.side)
        assertEquals("카카오", a.name)
        assertEquals(3.0, a.quantity, 0.0)
        assertEquals(45_200.0, a.price, 0.0)
    }

    @Test fun usStockInDollars() {
        val a = p("애플(AAPL) 2주 구매 체결됐어요. 평균가 $189.25", "viva.republica.toss")!!
        assertEquals(Side.BUY, a.side)
        assertEquals(Market.US_STOCK, a.market)
        assertEquals("AAPL", a.symbol)
        assertEquals(189.25, a.price, 1e-9)
    }

    @Test fun coinFromAnExchange() {
        val a = p("[비트코인] 매수 주문이 체결되었습니다. 체결수량 0.0015 BTC, 체결가격 95,000,000 KRW", "com.dunamu.exchange.upbit")!!
        assertEquals(Market.CRYPTO, a.market)
        assertEquals(0.0015, a.quantity, 1e-12)
        assertEquals(95_000_000.0, a.price, 0.0)
        assertEquals("비트코인", a.name)
    }

    @Test fun coinTicker() {
        val a = p("KRW-ETH 매도 체결 0.5 ETH @ 4,200,000")!!
        assertEquals(Side.SELL, a.side)
        assertEquals("ETH", a.symbol)
        assertEquals(Market.CRYPTO, a.market)
        assertEquals(4_200_000.0, a.price, 0.0)
    }

    @Test fun notFills() {
        assertNull(p("삼성전자(005930) 매수 주문이 접수되었습니다. 10주"))
        assertNull(p("카카오 매도 미체결 3주"))
        assertNull(p("비트코인이 목표가에 도달했어요. 매수해 보세요"))
        assertNull(p("오늘의 체결 강도 상위 종목"))
        // Both sides named: not sure which.
        assertNull(p("매수 10주 매도 5주 체결 삼성전자"))
    }
}

/** [INV-32] The waiting list: kept as lines, the same fill only once, the oldest dropped past the cap. */
class TradeAlertsTest {
    private val zone = java.time.ZoneId.of("Asia/Seoul")
    private fun pending(at: Long, qty: Double = 1.0) = com.bamnat.core.invest.TradeAlerts.Pending(
        at, "업비트", Side.BUY, "BTC", "비트코인", qty, 90_000_000.0, Market.CRYPTO)

    @Test fun roundTrip() {
        val list = listOf(pending(1_000), pending(2_000, 0.5).copy(name = "이름\t탭"))
        val back = com.bamnat.core.invest.TradeAlerts.decode(com.bamnat.core.invest.TradeAlerts.encode(list))
        assertEquals(2, back.size)
        assertEquals(list[0], back[0])
        assertEquals("이름 탭", back[1].name)
        assertEquals(emptyList<Any>(), com.bamnat.core.invest.TradeAlerts.decode("깨진 줄\n"))
    }

    @Test fun sameFillOnce() {
        val a = com.bamnat.core.invest.TradeAlerts.add(emptyList(), pending(0))!!
        assertNull(com.bamnat.core.invest.TradeAlerts.add(a, pending(60_000)))
        assertEquals(2, com.bamnat.core.invest.TradeAlerts.add(a, pending(60 * 60_000))!!.size)
        assertEquals(2, com.bamnat.core.invest.TradeAlerts.add(a, pending(60_000, 2.0))!!.size)
    }

    @Test fun capped() {
        var l = emptyList<com.bamnat.core.invest.TradeAlerts.Pending>()
        repeat(40) { l = com.bamnat.core.invest.TradeAlerts.add(l, pending(it * 3_600_000L))!! }
        assertEquals(com.bamnat.core.invest.TradeAlerts.MAX, l.size)
        assertEquals(10 * 3_600_000L, l.first().at)
    }

    @Test fun becomesATradeWithItsApp() {
        val at = java.time.ZonedDateTime.of(2026, 10, 22, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        val t = pending(at).toTrade(accountId = 3, fxRate = 1400.0, zone = zone)
        assertEquals(java.time.LocalDate.of(2026, 10, 22), t.date)
        assertEquals(9 * 60 + 30, t.minute)
        assertEquals(listOf("알림:업비트"), t.tags)
        assertEquals(1.0, t.fxRate, 0.0)
        assertEquals("업비트", com.bamnat.core.invest.TradeAlerts.sourceOf(t))
        assertEquals("코인", com.bamnat.core.invest.TradeAlerts.badge(t.market))
    }
}
