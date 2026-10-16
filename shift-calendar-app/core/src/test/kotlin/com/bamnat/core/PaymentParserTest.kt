package com.bamnat.core

import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.Job
import com.bamnat.core.money.MemoAmounts
import com.bamnat.core.money.MoneyAlerts
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import com.bamnat.core.money.MoneyStats
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.money.PaymentParser.Type
import com.bamnat.core.money.UpcomingPayments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/** [LEDG-02][LEDG-03] The 살림왕 parser tests, same synthetic texts (not real customers). */
class PaymentParserTest {
    private val now = LocalDateTime.of(2026, 10, 15, 12, 0)
    private fun p(s: String) = (PaymentParser.parse(s, now) as PaymentParser.Result.Ok).parsed
    private fun fails(s: String) = PaymentParser.parse(s, now) is PaymentParser.Result.Fail

    @Test fun shinhanOneLine() {
        val r = p("[Web발신]\n신한카드(1234)승인 홍*동 12,500원(일시불)10/15 13:20 스타벅스 누적1,234,567원")
        assertEquals(MoneyKind.EXPENSE, r.kind)
        assertEquals(12_500L, r.amount)
        assertEquals("신한", r.issuer)
        assertEquals("1234", r.last4)
        assertEquals(LocalDate.of(2026, 10, 15), r.date)
        assertEquals(LocalTime.of(13, 20), r.time)
        assertEquals("스타벅스", r.merchant)
        assertEquals(1_234_567L, r.cumulative)
        assertEquals("카페", r.category)
        assertTrue(r.confident)
    }

    @Test fun kbSeveralLines() {
        val r = p("[Web발신]\nKB국민카드1234승인\n홍*동님\n35,000원 일시불\n10/15 12:30\n이마트\n누적 500,000원")
        assertEquals(35_000L, r.amount)
        assertEquals("KB국민", r.issuer)
        assertEquals("이마트", r.merchant)
    }

    @Test fun installments() {
        val r = p("[현대카드] 홍길동님 10/14 09:12 쿠팡 450,000원 3개월 승인")
        assertEquals(3, r.installments)
        assertEquals(450_000L, r.amount)
        assertEquals("쿠팡", r.merchant)
    }

    @Test fun cancelIsNegative() {
        val r = p("신한카드(1234)취소 홍*동 12,500원 10/15 14:00 스타벅스")
        assertEquals(Type.CANCEL, r.type)
        assertEquals(-12_500L, PaymentParser.signedAmount(r))
        val target = MoneyEntry(id = 7, date = LocalDate.of(2026, 10, 15), kind = MoneyKind.EXPENSE, amount = 12_500, category = "카페", merchant = "스타벅스")
        assertEquals(7L, PaymentParser.findCancelTarget(r, r.date!!, listOf(target))?.id)
    }

    @Test fun issuerThenDigitsAndPlaceWithNumber() {
        val r = p("삼성1234승인 홍*동 89,000원 일시불 10/13 18:22 GS25 강남점 누적 1,000,000원")
        assertEquals("1234", r.last4)
        assertEquals("GS25 강남점", r.merchant)
        assertEquals("편의점", r.category)
    }

    @Test fun bankWithdrawIsTransferAndBalanceIsNotTheAmount() {
        val r = p("[Web발신]\n신한 10/15 14:00\n110-***-123456\n출금 50,000\n잔액 1,000,000\n김철수")
        assertEquals(MoneyKind.TRANSFER, r.kind)
        assertEquals(50_000L, r.amount)
        assertEquals(1_000_000L, r.balance)
        assertEquals("김철수", r.merchant)
    }

    @Test fun depositIsIncomeAndSalaryIsSalary() {
        val r = p("하나 10/15 09:00 123-456***-78 입금 3,200,000 잔액 4,000,000 (주)회사급여")
        assertEquals(MoneyKind.INCOME, r.kind)
        assertEquals(3_200_000L, r.amount)
        assertEquals("급여", r.category)
    }

    @Test fun foreignNeedsWon() {
        val r = p("우리(5678)승인 USD 12.99 10/12 08:00 NETFLIX.COM")
        assertTrue(r.needsWon)
        assertNull(r.amount)
        assertEquals(PaymentParser.Foreign("USD", 12.99), r.foreign)
        assertEquals(LocalDate.of(2026, 10, 12), r.date)
        assertEquals("구독", r.category)
    }

    @Test fun declinedAndNoAmountFail() {
        assertTrue(fails("신한카드 승인거절 잔액부족 10/15"))
        assertTrue(fails("안녕하세요 오늘 회의는 3시입니다"))
    }

    @Test fun decemberTextInJanuaryIsLastYear() {
        val r = (PaymentParser.parse("신한카드(1234)승인 10,000원 12/30 10:00 서점", LocalDateTime.of(2027, 1, 3, 10, 0)) as PaymentParser.Result.Ok).parsed
        assertEquals(LocalDate.of(2026, 12, 30), r.date)
    }

    @Test fun splitsPastedTexts() {
        assertEquals(2, PaymentParser.splitMessages("[Web발신]\nA카드 1,000원 승인\n[Web발신]\nB카드 2,000원 승인").size)
        assertEquals(3, PaymentParser.splitMessages("신한 1,000원 승인 스타벅스\n국민 2,000원 승인 이마트\n현대 3,000원 승인 쿠팡").size)
        assertEquals(2, PaymentParser.splitMessages("신한 1,000원 승인 스타벅스\n\n국민 2,000원 승인 이마트").size)
    }

    @Test fun learnedRuleWins() {
        assertEquals("식비", PaymentParser.guessCategory("스타벅스", mapOf("스타벅스" to "식비")))
        assertEquals("기타", PaymentParser.guessCategory("알 수 없는 가게"))
    }

    @Test fun duplicateWithinTenMinutes() {
        val r = p("신한카드(1234)승인 홍*동 12,500원(일시불)10/15 13:25 스타벅스")
        val a = MoneyEntry(date = LocalDate.of(2026, 10, 15), kind = MoneyKind.EXPENSE, amount = 12_500, category = "카페",
            merchant = "스타벅스", time = LocalTime.of(13, 20), cardLast4 = "1234")
        assertNotNull(PaymentParser.findDuplicate(r, r.date!!, listOf(a)))
        assertNull(PaymentParser.findDuplicate(r, r.date!!, listOf(a.copy(time = LocalTime.of(15, 0)))))
    }

    @Test fun looksFinancialFiltersChat() {
        assertTrue(PaymentParser.looksFinancial("신한카드(1234)승인 12,500원 스타벅스"))
        assertTrue(!PaymentParser.looksFinancial("엄마: 저녁 먹었어?"))
    }

    @Test fun notificationEntryWaitsForReview() {
        val e = PaymentParser.toEntry(p("[현대카드] 홍길동님 10/14 09:12 쿠팡 450,000원 3개월 승인"), LocalDate.of(2026, 10, 15), MoneySource.NOTIFICATION)!!
        assertEquals(LocalDate.of(2026, 10, 14), e.date)
        assertEquals(false, e.reviewed)
        assertEquals("3개월 할부 · 현대", e.note)
    }
}

/** [LEDG-05][WORK-05][LEDG-04][WORK-02] Fixed costs, alerts, memo amounts and places. */
class MoneyPlansTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val rent = FixedCost(id = 1, name = "월세", amount = 500_000, day = 31)
    private val card = FixedCost(id = 2, name = "신한카드", amount = 800_000, day = 1, kind = MoneyKind.TRANSFER, category = "카드값")

    @Test fun dayPastMonthEndIsTheLastDay() {
        assertEquals(LocalDate.of(2026, 2, 28), rent.dueIn(YearMonth.of(2026, 2)))
    }

    @Test fun upcomingSkipsRecordedAndCardBillIsNotSpending() {
        val up = UpcomingPayments.within(listOf(rent, card), emptyList(), today)
        assertEquals(listOf("월세", "신한카드"), up.map { it.cost.name })
        assertEquals(3, up[0].daysLeft)
        assertEquals(500_000L, UpcomingPayments.leftThisMonth(listOf(rent, card), emptyList(), today))
        val paid = UpcomingPayments.entry(rent, LocalDate.of(2026, 10, 31))
        assertEquals(listOf("신한카드"), UpcomingPayments.within(listOf(rent, card), listOf(paid), today).map { it.cost.name })
    }

    @Test fun alertsComeTogetherAtTheSameMinute() {
        val job = Job(id = 5, date = LocalDate.of(2026, 10, 27), title = "도배", amount = 300_000)
        val next = MoneyAlerts.next(listOf(job), listOf(rent), emptyList(), today.atTime(9, 30))
        assertEquals(listOf("unpaid-5-0"), next.map { it.key })
        assertEquals(LocalDateTime.of(2026, 10, 28, 10, 0), next[0].at)
        // Three days before the 31st at 9:00 and the 7-day nudge are later.
        val after = MoneyAlerts.next(listOf(job), listOf(rent), emptyList(), next[0].at)
        assertEquals(LocalDateTime.of(2026, 10, 31, 9, 0), after[0].at)
        assertTrue(MoneyAlerts.dueAt(listOf(job.copy(balancePaid = true)), listOf(rent), emptyList(), next[0].at).isEmpty())
    }

    @Test fun memoAmounts() {
        val f = MemoAmounts.find("+50,000 대리 팁\n수입 5만원\n점심 지출 3만 5천원\n2026년 계획\n회의 3시")
        assertEquals(listOf(50_000L, 50_000L, 35_000L), f.map { it.amount })
        assertEquals(listOf(MoneyKind.INCOME, MoneyKind.INCOME, MoneyKind.EXPENSE), f.map { it.kind })
        val written = MoneyEntry(date = today, kind = MoneyKind.INCOME, amount = 50_000, category = "부수입", source = MoneySource.MEMO)
        assertEquals(listOf(35_000L), MemoAmounts.pending("수입 5만원\n지출 3만 5천원", listOf(written)).map { it.amount })
    }

    @Test fun placesAndFrequentAmounts() {
        val m = YearMonth.of(2026, 10)
        fun e(d: Int, amount: Long, place: String) = MoneyEntry(date = m.atDay(d), kind = MoneyKind.EXPENSE, amount = amount, category = "카페", merchant = place)
        val list = listOf(e(1, 4_500, "스타벅스"), e(2, 4_500, "스타벅스"), e(3, 60_000, "이마트"), e(4, 4_500, "스타벅스"), e(5, 2_000, "GS25"))
        val places = MoneyStats.places(list, m)
        assertEquals("스타벅스", places[0].merchant)
        assertEquals(3, places[0].count)
        assertEquals(listOf(4_500L), MoneyStats.frequentAmounts(list, MoneyKind.EXPENSE, m.atDay(28)))
    }
}
