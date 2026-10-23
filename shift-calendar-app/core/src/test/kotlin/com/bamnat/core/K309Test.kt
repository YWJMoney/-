package com.bamnat.core

import com.bamnat.core.imports.AttendanceField
import com.bamnat.core.imports.AttendanceImport
import com.bamnat.core.imports.DutyCell
import com.bamnat.core.imports.DutyCodes
import com.bamnat.core.imports.DutyType
import com.bamnat.core.invest.AccountType
import com.bamnat.core.invest.InvestAccount
import com.bamnat.core.invest.InvestBook
import com.bamnat.core.invest.InvestCsv
import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Portfolio
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.Trade
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayIncome
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.PayPeriod
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.Payslip
import com.bamnat.core.pay.PayslipRead
import com.bamnat.core.pay.SlipAudit
import com.bamnat.core.pay.SlipLine
import com.bamnat.core.pay.SlipRole
import com.bamnat.core.pay.TakeHome
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** 1.23 돈·투자·가져오기 (row 309) and 내 월급 맞나요 (row 312 줄 46). */
class K309Test {
    private val monthly = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
        taxMode = TaxMode.INSURANCE_4)

    @Test fun `the ledger counts the take-home that lands on the pay day`() {
        // 말일 마감, 다음 달 10일 지급: October's pay arrives in November.
        val p = monthly.copy(period = PayPeriod(payDay = 10, payNextMonth = true), ledgerTakeHome = true)
        assertEquals(listOf(YearMonth.of(2026, 10)), PayIncome.payMonthsIn(p, YearMonth.of(2026, 11)))
        val paid = PayIncome.paidIn(p, YearMonth.of(2026, 11)) { 3_000_000 }.single()
        assertEquals(YearMonth.of(2026, 10), paid.payMonth)
        assertEquals(TakeHome.estimate(3_000_000, TaxMode.INSURANCE_4, 1, LocalDate.of(2026, 10, 1), 0, TakeHome.nonTaxable(p, YearMonth.of(2026, 10))).net, paid.net)
        assertTrue(paid.net < paid.gross)
        // No pay day: the month's own pay.
        assertEquals(listOf(YearMonth.of(2026, 11)), PayIncome.payMonthsIn(monthly, YearMonth.of(2026, 11)))
        // A month the calculation fails for counts nothing.
        assertTrue(PayIncome.paidIn(p, YearMonth.of(2026, 11)) { null }.isEmpty())
    }

    @Test fun `the tax report lists foreign sales oldest lot first and the totals`() {
        val general = InvestAccount(1, "일반", AccountType.GENERAL)
        val isa = InvestAccount(2, "ISA", AccountType.ISA)
        fun us(day: Int, side: Side, q: Double, p: Double, id: Long, account: Long = 1) =
            Trade(id, account, LocalDate.of(2026, 3, day), symbol = "TSLA", market = Market.US_STOCK, side = side, quantity = q, price = p, fxRate = 1_000.0)
        val book = InvestBook(accounts = listOf(general, isa), trades = listOf(
            us(2, Side.BUY, 10.0, 100.0, 1), us(3, Side.BUY, 10.0, 200.0, 2), us(4, Side.SELL, 10.0, 500.0, 3),
            us(2, Side.BUY, 1.0, 100.0, 4, account = 2), us(4, Side.SELL, 1.0, 900.0, 5, account = 2),
        ))
        val csv = InvestCsv.taxReport(book, Portfolio.build(book), 2026)
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertTrue(lines[0], lines[0].startsWith("\"2026년 해외 주식"))
        assertEquals(InvestCsv.TAX_HEADER.joinToString(","), lines[1])
        // One sale (the ISA one is left out): 10주 at 500달러 against the first 10 at 100달러.
        assertTrue(csv, lines[2].startsWith("2026-03-04,일반,TSLA"))
        assertTrue(lines[2].contains(",4000000,"))
        assertFalse(lines[3].startsWith("2026"))
        assertTrue(csv.contains("과세표준,,,,,,,,1500000"))
        assertTrue(csv.contains("예상 세금 (지방소득세 포함 22%),,,,,,,,330000"))
        assertTrue(csv.contains("확인 필요"))
    }

    @Test fun `import problems name the row, the column and why`() {
        val rows = listOf(
            listOf("날짜", "출근", "퇴근", "근무시간"),
            listOf("2026-10-01", "09:00", "18:00", "8"),
            listOf("어제", "09:00", "18:00", "8"),
            listOf("", "", "", ""),
            listOf("2026-10-02", "아홉시", "18:00", "여덟"),
            listOf("2026-10-01", "09:00", "", ""),
        )
        val map = AttendanceImport.guess(rows)!!
        val p = AttendanceImport.problems(rows, map, 2026)
        assertEquals(listOf(3 to AttendanceField.DATE), p.filter { it.row == 3 }.map { it.row to it.field })
        assertTrue(p.any { it.row == 5 && it.field == AttendanceField.START && it.cell == "아홉시" })
        assertTrue(p.any { it.row == 5 && it.field == AttendanceField.HOURS })
        assertTrue(p.any { it.row == 6 && it.reason.contains("2번째 줄과 같은 날") })
        assertTrue(p.any { it.row == 6 && it.reason.contains("하나만") })
        assertTrue(p.none { it.row == 4 })
        assertTrue(AttendanceImport.problems(rows.take(2), map, 2026).isEmpty())
    }

    @Test fun `police and fire roster words are read`() {
        val codes = DutyCodes(listOf(
            DutyType(1, "주간", "주", false, LocalTime.of(9, 0)), DutyType(2, "야간", "야", false, LocalTime.of(18, 0), night = true),
            DutyType(3, "당직", "당", false, LocalTime.of(9, 0)), DutyType(4, "비번", "비", true), DutyType(5, "휴무", "휴", true),
        ))
        assertEquals(DutyCell.Shift(1), codes.match("일근"))
        assertEquals(DutyCell.Shift(2), codes.match("야간근무"))
        assertEquals(DutyCell.Shift(3), codes.match("당직근무"))
        assertEquals(DutyCell.Shift(4), codes.match("비"))
        assertTrue(codes.match("비번일") is DutyCell.Shift)
        // A lone 일 is 일요일 in a weekday row, never a code.
        assertNull(codes.match("일"))
    }

    @Test fun `each week says whether it earned 주휴`() {
        val monday = LocalDate.of(2026, 11, 2)
        val hourly = monthly.copy(wageType = WageType.HOURLY, base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 10_320))))
        // Mon~Wed 8시간 (24h a week), a day missed in the second week, Tuesdays only in the last weeks... kept simple:
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(1L, 1L, 1L, null, null, null, null)),
            listOf(10, 11).map { com.bamnat.core.model.DayEntry(LocalDate.of(2026, 11, it), override = com.bamnat.core.model.ShiftOverride.Off) })
        val pay = PayCalculator().calculate(hourly, YearMonth.of(2026, 11), r::resolve)
        assertTrue(pay.lines.joinToString { it.label + ":" + it.formula }, pay.holidayWeeks.isNotEmpty())
        val first = pay.holidayWeeks.first { it.from == monday }
        assertTrue(first.earned)
        assertTrue(first.text.startsWith("주휴 "))
        val second = pay.holidayWeeks.first { it.from == monday.plusDays(7) }
        assertFalse(second.earned)
        assertTrue(second.text, second.text.contains("15시간 미만"))
        assertEquals(pay.holidayWeeks.count { it.earned }, pay.lines.single { it.label == "주휴수당" }.formula.substringBefore("주").trim().toInt())
    }

    @Test fun `the payslip's deductions go next to the estimate`() {
        val read = PayslipRead(listOf(
            SlipLine("지급총액", SlipRole.GROSS, 3_000_000),
            SlipLine("국민연금", SlipRole.DEDUCTION, 142_500),
            SlipLine("건강보험", SlipRole.DEDUCTION, 107_850),
            SlipLine("장기요양보험", SlipRole.DEDUCTION, 14_170),
            SlipLine("고용보험", SlipRole.DEDUCTION, 27_000),
            SlipLine("소득세", SlipRole.DEDUCTION, 74_350),
            SlipLine("지방소득세", SlipRole.DEDUCTION, 7_430),
            SlipLine("노조비", SlipRole.DEDUCTION, 10_000),
        ))
        val d = SlipAudit.deductions(read, monthly, YearMonth.of(2026, 10))
        assertEquals(listOf("국민연금", "건강보험", "장기요양", "고용보험", TakeHome.TAX_LABEL, TakeHome.LOCAL_TAX_LABEL), d.map { it.name })
        assertEquals(142_500L, d[0].slip)
        assertEquals(14_170L, d.single { it.name == "장기요양" }.slip)
        assertEquals(7_430L, d.last().slip)
        d.forEach { assertTrue(it.name, it.estimate != null) }
        assertEquals("지방소득세", SlipAudit.deductionOf("지방 소득세"))
        assertNull(SlipAudit.deductionOf("노조비"))
    }

    @Test fun `pay lines that differ fall in two baskets`() {
        val pay = MonthPay(YearMonth.of(2026, 10), listOf(
            PayLine(LineKind.BASE, "기본급", 2_500_000, ""), PayLine(LineKind.OVERTIME, "연장근로수당", 300_000, ""),
            PayLine(LineKind.NIGHT, "야간근로수당", 50_000, ""), PayLine(LineKind.ALLOWANCE, "식대", 200_000, ""),
        ), 14_354, "2026.2", emptyList(), emptyList())
        val gaps = SlipAudit.gaps(Payslip(3_100_000, base = 2_500_000, overtimePay = 250_000, nightPay = 90_000,
            allowances = mapOf("식대" to 200_000, "직책수당" to 100_000)), pay)
        assertEquals(listOf("연장근로수당" to SlipAudit.Basket.COMPANY_LESS, "야간근로수당" to SlipAudit.Basket.NOT_ENTERED,
            "직책수당" to SlipAudit.Basket.NOT_ENTERED), gaps.map { it.label to it.basket })
        assertEquals(-50_000L, gaps[0].diff)
        assertTrue(SlipAudit.ASK_NOTE.contains("1350"))
    }
}
