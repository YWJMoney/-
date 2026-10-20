package com.bamnat.core

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.MoneyAlerts
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.money.ShopCapture
import com.bamnat.core.money.TaxDeadlines
import com.bamnat.core.money.WorkCsv
import com.bamnat.core.work.CardSettlement
import com.bamnat.core.work.CashDrawer
import com.bamnat.core.work.SalesSplit
import com.bamnat.core.work.SalesTarget
import com.bamnat.core.work.ShopMoney
import com.bamnat.core.work.VatEstimate
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** [SHOP-01][SHOP-02][SHOP-03][SHOP-04][SHOP-05][SHOP-07][SHOP-08] A shop's money: methods, card deposits, cash, VAT, target, notices. */
class ShopMoneyTest {
    private fun d(day: Int, month: Int = 11) = LocalDate.of(2026, month, day)
    private val nov = YearMonth.of(2026, 11)

    @Test
    fun monthSplitsSalesByMethod() {
        val sales = listOf(
            DailySales(d(2), 100_000, card = 60_000, cash = 30_000, transfer = 10_000),
            DailySales(d(3), 50_000, delivery = 50_000),
            DailySales(d(4), 40_000),
        )
        val s = SalesSplit.of(nov, sales)
        assertEquals(listOf("카드" to 60_000L, "배달앱" to 50_000L, "나누지 않음" to 40_000L, "현금" to 30_000L, "계좌이체" to 10_000L), s.parts)
        assertEquals(190_000, s.total)
    }

    @Test
    fun cardMoneyComesBusinessDaysLaterLessTheFee() {
        // Friday 2026-11-27: two business days later is Tuesday 12-01.
        assertEquals(DayOfWeek.FRIDAY, d(27).dayOfWeek)
        assertEquals(LocalDate.of(2026, 12, 1), CardSettlement.payDate(d(27), 2, KoreanHolidays::isHoliday))
        // 추석 2026: 9/24–9/26 and the 대체공휴일; a sale on 9/23 waits past them.
        val after = CardSettlement.payDate(LocalDate.of(2026, 9, 23), 1, KoreanHolidays::isHoliday)
        assertTrue(after.isAfter(LocalDate.of(2026, 9, 26)))
        assertEquals(98_500, CardSettlement.afterFee(100_000, 150))
        val rules = ShopMoney(cardFeeBp = 150, settleDays = 2)
        val exp = CardSettlement.expected(listOf(DailySales(d(27), 100_000, card = 100_000), DailySales(d(30), 10_000, cash = 10_000)), rules, KoreanHolidays::isHoliday)
        assertEquals(1, exp.size)
        assertEquals(98_500, exp[0].amount)
        assertEquals(1, CardSettlement.waiting(exp, LocalDate.of(2026, 12, 1)).size)
        assertTrue(CardSettlement.waiting(exp, d(30)).isEmpty())
    }

    @Test
    fun cardDepositMatchesOneDayOrADaysTotal() {
        val rules = ShopMoney()
        val days = listOf(DailySales(d(2), 0, card = 200_000), DailySales(d(3), 0, card = 100_000), DailySales(d(4), 0, card = 300_000, cardSettled = true))
        val exp = CardSettlement.expected(days, rules) { false }
        // A little off the setting's fee still matches.
        assertEquals(listOf(d(2)), CardSettlement.match(197_200, d(4), exp).map { it.saleDate })
        assertTrue(CardSettlement.match(150_000, d(4), exp).isEmpty())
        // Settled days and far-away dates are never matched.
        assertTrue(CardSettlement.match(295_500, d(6), exp).isEmpty())
        assertTrue(CardSettlement.match(197_000, d(25), exp).isEmpty())
        // Two days paid on one date together.
        val sameDay = CardSettlement.expected(listOf(DailySales(d(7), 0, card = 100_000), DailySales(d(8), 0, card = 100_000)), rules.copy(settleDays = 2)) { false }
        assertEquals(sameDay[0].payDate, sameDay[1].payDate)
        assertEquals(2, CardSettlement.match(197_000, sameDay[0].payDate, sameDay).size)
    }

    @Test
    fun cashDrawerSaysTheDifference() {
        val day = DailySales(d(2), 150_000, card = 100_000, cash = 50_000, cashCounted = 148_000)
        val cashAccount = setOf(3L)
        val spent = listOf(
            MoneyEntry(date = d(2), kind = MoneyKind.EXPENSE, amount = 2_000, category = "소모품", accountId = 3),
            MoneyEntry(date = d(2), kind = MoneyKind.EXPENSE, amount = 9_000, category = "재료비", accountId = 1),
        )
        val check = CashDrawer.check(day, openingCash = 100_000, cashSpent = CashDrawer.cashSpent(spent, d(2), cashAccount))
        assertEquals(148_000, check.expected)
        assertEquals(0L, check.difference)
        assertNull(CashDrawer.check(day.copy(cashCounted = null), 0).difference)
    }

    @Test
    fun vatIsRoughAndHalfYearly() {
        val sales = listOf(DailySales(d(2, 3), 1_100_000, costs = 100_000), DailySales(d(2, 8), 9_000_000))
        val entries = listOf(
            MoneyEntry(date = d(5, 3), kind = MoneyKind.EXPENSE, amount = 220_000, category = "재료비", proof = "세금계산서"),
            MoneyEntry(date = d(6, 3), kind = MoneyKind.EXPENSE, amount = 110_000, category = "소모품", proof = "간이영수증"),
        )
        val h1 = VatEstimate.of(d(10, 5), sales, entries, ShopMoney())
        assertEquals(1, h1.half)
        assertEquals(1_100_000, h1.sales)
        assertEquals(220_000, h1.provenExpenses)
        assertEquals(430_000, h1.expenses)
        assertEquals(80_000, h1.vat)
        assertEquals(LocalDate.of(2026, 7, 25), h1.due)
        val simple = VatEstimate.of(d(10, 5), sales, entries, ShopMoney(simpleTax = true, valueAddedPercent = 15))
        assertEquals(16_500, simple.vat)
        assertEquals(LocalDate.of(2027, 1, 25), VatEstimate.of(d(1, 9), sales, entries, ShopMoney()).due)
    }

    @Test
    fun taxRemindersGoToShopsAndFieldWork() {
        val today = LocalDate.of(2026, 7, 1)
        val shop = TaxDeadlines.alerts(today, setOf(WorkStyle.BUSINESS)).map { it.at }
        assertTrue(LocalDateTime.of(2026, 7, 18, 9, 0) in shop)
        assertTrue(LocalDateTime.of(2026, 7, 24, 9, 0) in shop)
        assertTrue(LocalDateTime.of(2027, 1, 18, 9, 0) in shop)
        assertTrue(LocalDateTime.of(2027, 5, 30, 9, 0) in shop)
        val field = TaxDeadlines.alerts(today, setOf(WorkStyle.FIELD))
        assertTrue(field.isNotEmpty() && field.all { it.title.contains("종합소득세") })
        assertTrue(TaxDeadlines.alerts(today, setOf(WorkStyle.SHIFT)).isEmpty())
        val viaAlerts = MoneyAlerts.all(emptyList(), emptyList(), emptyList(), today, styles = setOf(WorkStyle.BUSINESS))
        assertTrue(viaAlerts.any { it.key == "tax-2026-07-25-7" })
        assertTrue(MoneyAlerts.all(emptyList(), emptyList(), emptyList(), today).none { it.key.startsWith("tax") })
    }

    @Test
    fun targetSplitsWhatIsLeftOverOpenDays() {
        val sales = listOf(DailySales(d(2), 3_000_000), DailySales(d(20), 1_000_000))
        val open = { x: LocalDate -> x.dayOfWeek != DayOfWeek.MONDAY }
        val p = SalesTarget.of(10_000_000, nov, sales, d(20), open)!!
        assertEquals(4_000_000, p.achieved)
        assertEquals(40, p.percent)
        assertEquals(6_000_000, p.remaining)
        // 21–30 November without Mondays (23, 30): 8 days.
        assertEquals(8, p.openDaysLeft)
        assertEquals(750_000, p.perDay)
        assertEquals(0, SalesTarget.of(10_000_000, nov, sales, d(5, 12), open)!!.openDaysLeft)
        assertNull(SalesTarget.of(0, nov, sales, d(20), open))
    }

    @Test
    fun shopAppsAddToSalesAndBankDepositsAreSettlements() {
        val order = ShopCapture.read("[배민] 신규 주문이 접수되었어요\n주문금액 23,500원", "com.sampleapp.woowahan.ceo", "배민사장님")!!
        assertEquals(ShopCapture.Bucket.DELIVERY, order.bucket)
        assertEquals(23_500, order.amount)
        val card = ShopCapture.read("카드 승인 12,000원 (신한)", "im.toss.place.pos", "토스 플레이스")!!
        assertEquals(ShopCapture.Bucket.CARD, card.bucket)
        assertEquals(-12_000, ShopCapture.read("카드 승인 취소 12,000원", "", "토스 플레이스")!!.amount)
        // Other apps, and unclear texts from shop apps, are left to the usual reading.
        assertNull(ShopCapture.read("카드 승인 12,000원", "com.kbcard.cxh.appcard", "KB Pay"))
        assertNull(ShopCapture.read("오늘 매출 리포트가 도착했어요", "", "배민사장님"))
        assertNull(ShopCapture.read("리뷰 이벤트 5,000원 쿠폰", "", "쿠팡이츠 스토어"))
        val day = ShopCapture.add(DailySales(d(2), 50_000), d(2), order)
        assertEquals(50_000, day.cash)
        assertEquals(23_500, day.delivery)
        assertEquals(73_500, day.sales)
        val undone = ShopCapture.add(day, d(2), order.copy(amount = -30_000))
        assertEquals(0, undone.delivery)
        assertEquals(50_000, undone.sales)

        val text = "[Web발신]\n신한 11/04 10:12\n입금 197,200원\n신한카드 정산\n잔액 1,000,000원"
        val parsed = (PaymentParser.parse(text, LocalDateTime.of(2026, 11, 4, 10, 12)) as? PaymentParser.Result.Ok)?.parsed
        assertEquals(197_200L, ShopCapture.settlement(text, parsed))
        val salary = "[Web발신]\n신한 11/04 10:12\n입금 197,200원\n홍길동\n잔액 1,000,000원"
        assertNull(ShopCapture.settlement(salary, (PaymentParser.parse(salary, LocalDateTime.of(2026, 11, 4, 10, 12)) as? PaymentParser.Result.Ok)?.parsed))
    }

    @Test
    fun salesCsvKeepsMethods() {
        val csv = WorkCsv.sales(listOf(DailySales(d(2), 100_000, costs = 10_000, card = 60_000, cash = 40_000, cashCounted = 40_000, cardSettled = true)))
        assertEquals("2026-11-02,100000,60000,40000,0,0,10000,90000,40000,예,", csv.lines()[1].trimEnd('\r'))
    }
}
