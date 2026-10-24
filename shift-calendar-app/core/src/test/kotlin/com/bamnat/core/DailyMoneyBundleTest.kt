package com.bamnat.core

import com.bamnat.core.money.DailySales
import com.bamnat.core.money.IncomeTaxNote
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobStatus
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.Receivable
import com.bamnat.core.money.ReceivableKind
import com.bamnat.core.money.ReceivablePayment
import com.bamnat.core.money.ReceiptReader
import com.bamnat.core.money.WorkCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** 1.22 묶음 E: money parts that run in core (LEDG-26, LEDG-27). */
class DailyMoneyBundleTest {
    private fun d(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)

    @Test
    fun receivablesCsvHasEveryLineWithWhatIsLeftAndKeepsFormulasOut() {
        val list = listOf(
            Receivable(id = 2, who = "=SUM(A1)", title = "10월, 납품", amount = 300_000, date = d(10, 5), due = d(10, 20),
                payments = listOf(ReceivablePayment(date = d(10, 10), amount = 100_000))),
            Receivable(id = 1, who = "민지", amount = 50_000, date = d(9, 1), kind = ReceivableKind.LOAN, note = "점심 \"반\""),
        )
        val csv = WorkCsv.receivables(list, d(10, 22))
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(WorkCsv.RECEIVABLES_HEADER.joinToString(","), lines[0])
        // Oldest first.
        assertTrue(lines[1].startsWith("2026-09-01,민지,,빌려준 돈,50000,0,50000,"))
        assertTrue(lines[1].endsWith("아니요,\"점심 \"\"반\"\"\""))
        assertTrue(lines[2].startsWith("2026-10-05,'=SUM(A1),\"10월, 납품\",외상·미수 매출,300000,100000,200000,2026-10-20,"))
        assertTrue(lines[2].contains(",2026-10-10,"))
        assertEquals("bamnat-receivables.csv", WorkCsv.fileName("receivables", null, null))
    }

    @Test
    fun incomeTaxNoteAddsTheYearAndSplitsTheWithheldTaxes() {
        val jobs = listOf(
            Job(id = 1, date = d(3, 2), title = "촬영", amount = 1_000_000, withholding = true, costs = 50_000),
            Job(id = 2, date = d(4, 2), title = "타일", amount = 333_333, withholding = true),
            Job(id = 3, date = d(5, 2), title = "취소", amount = 900_000, withholding = true, status = JobStatus.CANCELLED),
            Job(id = 4, date = d(5, 2, 2025), title = "작년", amount = 900_000, withholding = true),
            Job(id = 5, date = d(6, 2), title = "현금", amount = 200_000),
        )
        val sales = listOf(DailySales(d(7, 1), sales = 400_000, costs = 100_000), DailySales(d(7, 2), sales = 0, costs = 0))
        val entries = listOf(
            MoneyEntry(date = d(8, 1), kind = MoneyKind.EXPENSE, amount = 30_000, category = "재료"),
            MoneyEntry(date = d(8, 1), kind = MoneyKind.INCOME, amount = 999_999, category = "기타"),
        )
        val y = IncomeTaxNote.of(2026, jobs, sales, entries)
        assertEquals(1_000_000 + 333_333 + 200_000 + 400_000L, y.income)
        assertEquals(50_000 + 100_000 + 30_000L, y.costs)
        // 3%: 30,000 + 9,990 (10원 미만 버림); 지방소득세 a tenth of each: 3,000 + 990.
        assertEquals(39_990L, y.withheldIncomeTax)
        assertEquals(3_990L, y.withheldLocalTax)
        assertEquals(jobs[0].withheld + jobs[1].withheld, y.withheld)
        assertEquals(3, y.jobs)
        assertEquals(1, y.salesDays)
        assertEquals(d(5, 1, 2027), y.fileFrom)
        // 2027-05-31 is a Monday.
        assertEquals(d(5, 31, 2027), y.fileTo)
        assertFalse(y.isEmpty)
        assertTrue(IncomeTaxNote.of(2024, jobs, sales, entries).isEmpty)
        // A 5월 31일 on a Sunday moves to the Monday (2026 신고 for 2025).
        assertEquals(d(6, 1, 2026), IncomeTaxNote.of(2025, jobs, sales, entries).fileTo)
        assertTrue(IncomeTaxNote.NOT_ADVICE.contains("세무 조언이 아니"))
    }

    @Test
    fun cardReceiptGivesTheTotalDayTimeAndShop() {
        val text = """
            스타벅스 강남역점
            사업자번호 123-45-67890
            서울 강남구 테헤란로 1 TEL 02-123-4567
            2026-10-21 08:14:33
            아메리카노 2 4,500 9,000
            카페라떼 1 5,000 5,000
            공급가액 12,727
            부가세 1,273
            합계 14,000
            신용카드 14,000
            승인번호 12345678
        """.trimIndent()
        val r = ReceiptReader.read(text, d(10, 22))
        assertEquals(14_000L, r.amount)
        assertTrue(r.sure)
        assertEquals(d(10, 21), r.date)
        assertEquals(LocalTime.of(8, 14), r.time)
        assertEquals("스타벅스 강남역점", r.merchant)
    }

    @Test
    fun labelledShopAndPaidAmountWinOverCashGivenAndChange() {
        val text = """
            [매출전표]
            가맹점명: 가나다마트 (T.031-000-0000)
            일시: 26/10/20 19:02
            품명 단가 수량 금액
            라면 1,200 5 6,000
            결제금액 : 6,000원
            받은금액 10,000
            거스름돈 4,000
        """.trimIndent()
        val r = ReceiptReader.read(text, d(10, 22))
        assertEquals(6_000L, r.amount)
        assertTrue(r.sure)
        assertEquals("가나다마트", r.merchant)
        assertEquals(d(10, 20), r.date)
        assertEquals(LocalTime.of(19, 2), r.time)
    }

    @Test
    fun totalOnTheNextRowAndReceiptsWithoutATotal() {
        val split = ReceiptReader.read("라마바주유소\n총 결제금액\n55,000\n2026년 10월 3일", d(10, 22))
        assertEquals(55_000L, split.amount)
        assertTrue(split.sure)
        assertEquals(d(10, 3), split.date)
        assertEquals("라마바주유소", split.merchant)
        // No line says it is the total: the largest amount, marked as a guess.
        val guess = ReceiptReader.read("동네 철물점\n2026.10.19\n못 3,000\n망치 12,000", d(10, 22))
        assertEquals(12_000L, guess.amount)
        assertFalse(guess.sure)
        // A date in the future or a blank photo gives nothing to fill.
        assertNull(ReceiptReader.read("합계 9,000\n2027-12-01", d(10, 22)).date)
        assertTrue(ReceiptReader.read("", d(10, 22)).isEmpty)
    }
}
